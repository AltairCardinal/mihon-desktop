#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <bcrypt.h>
#include <io.h>
#include <fcntl.h>
#include <jni.h>
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>
#include "miniz/miniz.h"

namespace {
constexpr const char* VERSION = "miniz/174573d60290f447c13a2b1b3405de2b96e27d6c;abi=1;x64";
struct Page { std::string name; mz_uint index; std::string stamp; };
struct Lease {
    FILE* file = nullptr;
    mz_zip_archive zip{};
    bool initialized = false;
    std::vector<Page> pages;
    std::string fingerprint;
    std::mutex operation;
    ~Lease() { if (initialized) mz_zip_reader_end(&zip); if (file) fclose(file); }
};
std::mutex registryMutex;
std::unordered_map<jlong, std::shared_ptr<Lease>> registry;
std::atomic<jlong> nextHandle{1};

void fail(JNIEnv* env, const char* message, const char* type = "java/io/IOException") {
    if (!env->ExceptionCheck()) {
        auto cls = env->FindClass(type);
        if (cls) { env->ThrowNew(cls, message); env->DeleteLocalRef(cls); }
    }
}
std::wstring wide(JNIEnv* env, jstring value) {
    if (!value) throw std::runtime_error("Missing archive path");
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (!chars) throw std::runtime_error("Cannot read archive path");
    std::wstring result(reinterpret_cast<const wchar_t*>(chars), env->GetStringLength(value));
    env->ReleaseStringChars(value, chars);
    if (result.find(L'\0') != std::wstring::npos) throw std::runtime_error("Invalid archive path");
    return result;
}
struct Digest {
    BCRYPT_ALG_HANDLE algorithm = nullptr;
    Digest() {
        if (BCryptOpenAlgorithmProvider(&algorithm, BCRYPT_SHA256_ALGORITHM, nullptr, 0) < 0)
            throw std::runtime_error("Cannot initialize archive SHA-256");
    }
    ~Digest() { if (algorithm) BCryptCloseAlgorithmProvider(algorithm, 0); }
    std::string hex(const std::string& input) {
        unsigned char digest[32];
        if (BCryptHash(algorithm, nullptr, 0, (PUCHAR)input.data(), static_cast<ULONG>(input.size()), digest, 32) < 0)
            throw std::runtime_error("Cannot compute archive SHA-256");
        constexpr const char* digits = "0123456789abcdef";
        std::string output(64, '0');
        for (int i = 0; i < 32; ++i) { output[i * 2] = digits[digest[i] >> 4]; output[i * 2 + 1] = digits[digest[i] & 15]; }
        return output;
    }
};
unsigned short u16(const unsigned char* p) { return p[0] | (p[1] << 8); }
bool readAt(Lease& lease, mz_uint64 offset, void* destination, size_t size) {
    return mz_zip_read_archive_data(&lease.zip, offset, destination, size) == size;
}
// Only the timestamp and Unix uid/gid fields observed in the pinned source archive
// are admitted. Unicode path, ZIP64 and unknown fields return to the existing reader.
bool safeExtra(const std::vector<unsigned char>& extra, bool central) {
    size_t position = 0;
    bool timestamp = false, ownership = false;
    while (position < extra.size()) {
        if (extra.size() - position < 4) return false;
        auto tag = u16(extra.data() + position), size = u16(extra.data() + position + 2);
        position += 4;
        if (size > extra.size() - position) return false;
        const auto* data = extra.data() + position;
        if (tag == 0x5455 && !timestamp && size >= 1 && (data[0] & ~7) == 0) {
            timestamp = true;
            auto flags = data[0];
            auto expected = central ? 1 + ((flags & 1) ? 4 : 0) :
                1 + ((flags & 1) ? 4 : 0) + ((flags & 2) ? 4 : 0) + ((flags & 4) ? 4 : 0);
            if (size != expected) return false;
        } else if (tag == 0x7875 && !ownership && size >= 5 && data[0] == 1) {
            ownership = true;
            auto uid = data[1];
            if (!uid || uid > 8 || 2 + uid >= size) return false;
            auto gid = data[2 + uid];
            if (!gid || gid > 8 || size != 3 + uid + gid) return false;
        } else return false;
        position += size;
    }
    return true;
}
bool validateHeaders(Lease& lease, const mz_zip_archive_file_stat& stat, const std::string& name) {
    unsigned char central[46], local[30];
    const auto centralOffset = lease.zip.m_central_directory_file_ofs + stat.m_central_dir_ofs;
    if (!readAt(lease, centralOffset, central, sizeof central) ||
        !readAt(lease, stat.m_local_header_ofs, local, sizeof local)) return false;
    if (memcmp(central, "PK\1\2", 4) || memcmp(local, "PK\3\4", 4) ||
        u16(central + 28) != name.size() || u16(local + 26) != name.size() ||
        u16(local + 6) != stat.m_bit_flag || u16(local + 8) != stat.m_method) return false;
    for (int kind = 0; kind < 2; ++kind) {
        const bool isCentral = kind == 0;
        auto offset = isCentral ? centralOffset + sizeof central : stat.m_local_header_ofs + sizeof local;
        auto extraSize = u16(isCentral ? central + 30 : local + 28);
        if (extraSize > 4096) return false;
        std::string rawName(name.size(), '\0');
        if (!readAt(lease, offset, rawName.data(), rawName.size()) || rawName != name) return false;
        std::vector<unsigned char> extra(extraSize);
        if (extraSize && !readAt(lease, offset + name.size(), extra.data(), extra.size())) return false;
        if (!safeExtra(extra, isCentral)) return false;
    }
    return true;
}
bool snapshot(Lease& lease) {
    auto count = mz_zip_reader_get_num_files(&lease.zip);
    if (!count || count > 65535 || mz_zip_is_zip64(&lease.zip)) return false;
    size_t digits = 0, metadataCount = 0;
    std::string metadata;
    for (mz_uint index = 0; index < count; ++index) {
        mz_zip_archive_file_stat stat;
        if (!mz_zip_reader_file_stat(&lease.zip, index, &stat) || !stat.m_is_supported ||
            stat.m_is_directory || stat.m_is_encrypted || (stat.m_bit_flag & ~(8 | 2048)) ||
            (stat.m_method != 0 && stat.m_method != 8) ||
            mz_zip_reader_get_filename(&lease.zip, index, nullptr, 0) > sizeof stat.m_filename) return false;
        std::string name = stat.m_filename;
        if (name.size() + 1 != mz_zip_reader_get_filename(&lease.zip, index, nullptr, 0) ||
            !validateHeaders(lease, stat, name)) return false;
        if (name == "ComicInfo.xml") { if (++metadataCount > 1) return false; }
        else {
            auto dot = name.find('.');
            if (dot == std::string::npos || dot == 0 || dot > 32 || (digits && digits != dot) ||
                name.substr(dot) != ".webp" || name.find_first_not_of("0123456789") != dot) return false;
            digits = dot;
            lease.pages.push_back({name, index, {}});
        }
        if (index) metadata.push_back(1);
        metadata += name;
        metadata.append("\0false\0", 7);
        metadata += std::to_string(stat.m_uncomp_size); metadata.push_back(0);
        metadata += std::to_string(stat.m_comp_size); metadata.push_back(0);
        metadata += std::to_string(static_cast<int32_t>(stat.m_crc32)); metadata.push_back(0);
        metadata += stat.m_method == 0 ? "Store" : "Deflate";
        if (metadata.size() > 16 * 1024 * 1024) return false;
    }
    if (lease.pages.empty()) return false;
    std::sort(lease.pages.begin(), lease.pages.end(), [](const Page& a, const Page& b) { return a.name < b.name; });
    for (size_t i = 1; i < lease.pages.size(); ++i) if (lease.pages[i - 1].name == lease.pages[i].name) return false;
    Digest digest;
    lease.fingerprint = digest.hex(metadata);
    for (auto& page : lease.pages) page.stamp = digest.hex(lease.fingerprint + '\0' + page.name).substr(0, 32);
    return true;
}
std::shared_ptr<Lease> resolve(jlong handle) {
    std::lock_guard<std::mutex> lock(registryMutex);
    auto entry = registry.find(handle);
    if (entry == registry.end()) throw std::runtime_error("Archive handle is closed");
    return entry->second;
}
struct Output {
    JNIEnv* env;
    jobject thread;
    jmethodID interrupted;
    FILE* file;
    mz_uint64 written = 0;
    bool cancelled = false;
    size_t write(mz_uint64 offset, const void* bytes, size_t size) {
        if (env->CallBooleanMethod(thread, interrupted) || env->ExceptionCheck()) { cancelled = true; return 0; }
        if (offset != written || fwrite(bytes, 1, size, file) != size) return 0;
        written += size;
        return size;
    }
};
}

extern "C" JNIEXPORT jstring JNICALL Java_mihon_desktop_reader_DesktopWindowsMinizNative_version(JNIEnv* env, jclass) {
    return env->NewStringUTF(VERSION);
}
extern "C" JNIEXPORT jlong JNICALL Java_mihon_desktop_reader_DesktopWindowsMinizNative_open(JNIEnv* env, jclass, jstring path) {
    try {
        auto lease = std::make_shared<Lease>();
        auto name = wide(env, path);
        HANDLE raw = CreateFileW(name.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
            nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (raw == INVALID_HANDLE_VALUE) return 0;
        int fd = _open_osfhandle(reinterpret_cast<intptr_t>(raw), _O_RDONLY | _O_BINARY);
        if (fd == -1) { CloseHandle(raw); return 0; }
        lease->file = _fdopen(fd, "rb");
        if (!lease->file) { _close(fd); return 0; }
        if (!mz_zip_reader_init_cfile(&lease->zip, lease->file, 0, MZ_ZIP_FLAG_DO_NOT_SORT_CENTRAL_DIRECTORY)) return 0;
        lease->initialized = true;
        if (!snapshot(*lease)) return 0;
        auto handle = nextHandle.fetch_add(1);
        if (handle <= 0) throw std::runtime_error("Archive handle space exhausted");
        std::lock_guard<std::mutex> lock(registryMutex);
        registry.emplace(handle, lease);
        return handle;
    } catch (const std::bad_alloc&) { fail(env, "Archive metadata allocation failed", "java/lang/OutOfMemoryError"); }
      catch (const std::exception& error) { fail(env, error.what()); }
    return 0;
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_mihon_desktop_reader_DesktopWindowsMinizNative_snapshot(JNIEnv* env, jclass, jlong handle) {
    try {
        auto lease = resolve(handle);
        auto stringClass = env->FindClass("java/lang/String");
        if (!stringClass) return nullptr;
        auto result = env->NewObjectArray(static_cast<jsize>(1 + lease->pages.size() * 2), stringClass, nullptr);
        env->DeleteLocalRef(stringClass);
        if (!result) return nullptr;
        auto set = [&](jsize index, const std::string& value) {
            auto item = env->NewStringUTF(value.c_str());
            if (!item) return false;
            env->SetObjectArrayElement(result, index, item);
            env->DeleteLocalRef(item);
            return !env->ExceptionCheck();
        };
        if (!set(0, lease->fingerprint)) return nullptr;
        for (size_t i = 0; i < lease->pages.size(); ++i) {
            if (!set(static_cast<jsize>(1 + 2 * i), lease->pages[i].name) ||
                !set(static_cast<jsize>(2 + 2 * i), lease->pages[i].stamp)) return nullptr;
        }
        return result;
    } catch (const std::bad_alloc&) { fail(env, "Archive metadata allocation failed", "java/lang/OutOfMemoryError"); }
      catch (const std::exception& error) { fail(env, error.what()); }
    return nullptr;
}
extern "C" JNIEXPORT void JNICALL Java_mihon_desktop_reader_DesktopWindowsMinizNative_copy(JNIEnv* env, jclass, jlong handle, jint page, jstring destination) {
    try {
        auto lease = resolve(handle);
        std::lock_guard<std::mutex> lock(lease->operation);
        if (page < 0 || static_cast<size_t>(page) >= lease->pages.size()) throw std::runtime_error("Archive page index is out of range");
        auto threadClass = env->FindClass("java/lang/Thread");
        if (!threadClass) return;
        auto current = env->GetStaticMethodID(threadClass, "currentThread", "()Ljava/lang/Thread;");
        auto interrupted = env->GetMethodID(threadClass, "isInterrupted", "()Z");
        if (!current || !interrupted) { env->DeleteLocalRef(threadClass); return; }
        auto thread = env->CallStaticObjectMethod(threadClass, current);
        env->DeleteLocalRef(threadClass);
        if (!thread || env->ExceptionCheck()) return;
        auto name = wide(env, destination);
        auto file = std::unique_ptr<FILE, decltype(&fclose)>(_wfopen(name.c_str(), L"wb"), fclose);
        if (!file) { env->DeleteLocalRef(thread); throw std::runtime_error("Cannot create archive page destination"); }
        Output output{env, thread, interrupted, file.get()};
        auto index = lease->pages[page].index;
        auto ok = mz_zip_reader_extract_to_callback(&lease->zip, index,
            [](void* opaque, mz_uint64 offset, const void* bytes, size_t size) -> size_t {
                return static_cast<Output*>(opaque)->write(offset, bytes, size);
            }, &output, 0);
        env->DeleteLocalRef(thread);
        mz_zip_archive_file_stat stat;
        auto complete = mz_zip_reader_file_stat(&lease->zip, index, &stat) && output.written == stat.m_uncomp_size;
        auto closeResult = fclose(file.release());
        if (output.cancelled) { fail(env, "Archive page copy interrupted", "java/lang/InterruptedException"); return; }
        if (!ok || !complete || closeResult != 0) throw std::runtime_error("Archive page copy or CRC validation failed");
    } catch (const std::bad_alloc&) { fail(env, "Archive extraction allocation failed", "java/lang/OutOfMemoryError"); }
      catch (const std::exception& error) { fail(env, error.what()); }
}
extern "C" JNIEXPORT void JNICALL Java_mihon_desktop_reader_DesktopWindowsMinizNative_close(JNIEnv*, jclass, jlong handle) {
    std::shared_ptr<Lease> removed;
    { std::lock_guard<std::mutex> lock(registryMutex); auto entry = registry.find(handle);
      if (entry != registry.end()) { removed = std::move(entry->second); registry.erase(entry); } }
}
