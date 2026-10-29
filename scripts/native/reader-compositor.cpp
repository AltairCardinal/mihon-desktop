// Reader diagnostics: window WGC, or an explicit fixed reader-client region of DXGI output.
// No input or window changes. Output pixels outside that fixed region never leave the GPU.
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <d3d11.h>
#include <dxgi1_2.h>
#include <windows.graphics.capture.interop.h>
#include <windows.graphics.directx.direct3d11.interop.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Graphics.Capture.h>
#include <winrt/Windows.Graphics.DirectX.Direct3D11.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <fcntl.h>
#include <io.h>
#include <mutex>
#include <memory>
#include <stdexcept>
#include <thread>
#include <vector>

using namespace winrt;
using namespace winrt::Windows::Graphics::Capture;
using namespace winrt::Windows::Graphics::DirectX;
using namespace winrt::Windows::Graphics::DirectX::Direct3D11;
using ::Windows::Graphics::DirectX::Direct3D11::IDirect3DDxgiInterfaceAccess;

#pragma pack(push, 1)
struct Header {
    uint32_t magic = 0x4d574743;
    uint32_t width, height, contentWidth, contentHeight;
    int64_t time100ns;
};
#pragma pack(pop)
static_assert(sizeof(Header) == 28);

struct CaptureState {
    HWND hwnd{};
    DWORD pid{};
    std::atomic<bool> done{false}, failed{false};
    std::mutex drawing;
    com_ptr<ID3D11Device> device;
    com_ptr<ID3D11DeviceContext> context;
    com_ptr<ID3D11Texture2D> staging;
    UINT width = 0, height = 0;
};

static std::vector<uint8_t> readRegion(CaptureState& state, ID3D11Texture2D* texture, D3D11_BOX region) {
    D3D11_TEXTURE2D_DESC description{};
    texture->GetDesc(&description);
    if (region.right <= region.left || region.bottom <= region.top ||
        region.right > description.Width || region.bottom > description.Height ||
        description.Format != DXGI_FORMAT_B8G8R8A8_UNORM)
        throw std::runtime_error("GPU capture region or pixel format was rejected");
    const UINT width = region.right - region.left, height = region.bottom - region.top;
    if (uint64_t(width) * height * 4 > 64 * 1024 * 1024)
        throw std::runtime_error("Capture region exceeds the 64 MiB limit");
    if (!state.staging || width != state.width || height != state.height) {
        D3D11_TEXTURE2D_DESC target{};
        target.Width = width; target.Height = height;
        target.MipLevels = 1; target.ArraySize = 1;
        target.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
        target.SampleDesc.Count = 1; target.Usage = D3D11_USAGE_STAGING;
        target.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
        state.staging = nullptr;
        check_hresult(state.device->CreateTexture2D(&target, nullptr, state.staging.put()));
        state.width = width; state.height = height;
    }
    state.context->CopySubresourceRegion(state.staging.get(), 0, 0, 0, 0, texture, 0, &region);
    std::vector<uint8_t> pixels(size_t(width) * height * 4);
    D3D11_MAPPED_SUBRESOURCE mapped{};
    check_hresult(state.context->Map(state.staging.get(), 0, D3D11_MAP_READ, 0, &mapped));
    if (mapped.RowPitch < width * 4) {
        state.context->Unmap(state.staging.get(), 0);
        throw std::runtime_error("Mapped row pitch was smaller than the capture width");
    }
    for (UINT row = 0; row < height; ++row)
        memcpy(pixels.data() + size_t(row) * width * 4,
            static_cast<uint8_t*>(mapped.pData) + size_t(row) * mapped.RowPitch, size_t(width) * 4);
    state.context->Unmap(state.staging.get(), 0);
    return pixels;
}

static void writeFrame(std::vector<uint8_t> const& pixels, UINT width, UINT height, int64_t time, uint32_t magic) {
    const Header header{magic, width, height, width, height, time};
    if (fwrite(&header, sizeof(header), 1, stdout) != 1 || fwrite(pixels.data(), pixels.size(), 1, stdout) != 1 || fflush(stdout))
        throw std::runtime_error("Capture pipe closed");
}

static RECT clientRegion(HWND hwnd) {
    RECT client{}; POINT origin{};
    if (!GetClientRect(hwnd, &client) || !ClientToScreen(hwnd, &origin))
        throw std::runtime_error("Reader client region is unavailable");
    OffsetRect(&client, origin.x, origin.y);
    return client;
}

static bool contains(RECT const& outer, RECT const& inner) {
    return inner.right > inner.left && inner.bottom > inner.top && inner.left >= outer.left &&
        inner.top >= outer.top && inner.right <= outer.right && inner.bottom <= outer.bottom;
}

static int captureOutputRegion(CaptureState& state, RECT const& region, double seconds) {
    const auto initial = clientRegion(state.hwnd);
    if (!EqualRect(&initial, &region)) throw std::runtime_error("Requested reader client region does not match the owned window");
    const auto monitor = MonitorFromWindow(state.hwnd, MONITOR_DEFAULTTONULL);
    MONITORINFO info{sizeof(MONITORINFO)};
    if (!monitor || !GetMonitorInfoW(monitor, &info) || !contains(info.rcMonitor, region))
        throw std::runtime_error("Reader client region is not wholly inside one active display");
    if (!IsWindowVisible(state.hwnd) || IsIconic(state.hwnd) || GetForegroundWindow() != state.hwnd)
        throw std::runtime_error("Reader must be visible and foreground before output-region capture");

    com_ptr<IDXGIFactory1> factory;
    check_hresult(CreateDXGIFactory1(__uuidof(IDXGIFactory1), factory.put_void()));
    com_ptr<IDXGIAdapter1> adapter;
    com_ptr<IDXGIOutput1> output;
    for (UINT a = 0; !output; ++a) {
        com_ptr<IDXGIAdapter1> candidate;
        const auto result = factory->EnumAdapters1(a, candidate.put());
        if (result == DXGI_ERROR_NOT_FOUND) break;
        check_hresult(result);
        for (UINT o = 0; ; ++o) {
            com_ptr<IDXGIOutput> current;
            const auto found = candidate->EnumOutputs(o, current.put());
            if (found == DXGI_ERROR_NOT_FOUND) break;
            check_hresult(found);
            DXGI_OUTPUT_DESC description{};
            check_hresult(current->GetDesc(&description));
            if (description.Monitor == monitor && description.AttachedToDesktop) {
                if (description.Rotation != DXGI_MODE_ROTATION_IDENTITY)
                    throw std::runtime_error("Rotated output capture is not supported; no pixels were saved");
                output = current.as<IDXGIOutput1>(); adapter = candidate; break;
            }
        }
    }
    if (!output) throw std::runtime_error("Reader display output was not found");
    check_hresult(D3D11CreateDevice(adapter.get(), D3D_DRIVER_TYPE_UNKNOWN, nullptr,
        D3D11_CREATE_DEVICE_BGRA_SUPPORT, nullptr, 0, D3D11_SDK_VERSION, state.device.put(), nullptr, state.context.put()));
    com_ptr<IDXGIOutputDuplication> duplication;
    check_hresult(output->DuplicateOutput(state.device.get(), duplication.put()));
    LARGE_INTEGER frequency{};
    if (!QueryPerformanceFrequency(&frequency)) throw std::runtime_error("QPC frequency unavailable");
    const D3D11_BOX box{UINT(region.left - info.rcMonitor.left), UINT(region.top - info.rcMonitor.top), 0,
        UINT(region.right - info.rcMonitor.left), UINT(region.bottom - info.rcMonitor.top), 1};
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(static_cast<int>(seconds * 1000));
    auto holdStarted = std::chrono::steady_clock::time_point{};
    const auto active = [&] {
        DWORD owner = 0, affinity = 0;
        GetWindowThreadProcessId(state.hwnd, &owner);
        if (owner != state.pid || !IsWindow(state.hwnd)) return false;
        if ((GetWindowDisplayAffinity(state.hwnd, &affinity) && affinity) || IsIconic(state.hwnd)) return false;
        // WM_SETREDRAW intentionally clears WS_VISIBLE. Observe that hold, but stop when the
        // user hides, moves, or leaves the reader; never retarget another window or display.
        const bool held = GetPropW(state.hwnd, L"SysSetRedraw") != nullptr;
        if ((!IsWindowVisible(state.hwnd) && !held) || (!held && GetForegroundWindow() != state.hwnd)) return false;
        if (held) {
            if (holdStarted == std::chrono::steady_clock::time_point{}) holdStarted = std::chrono::steady_clock::now();
            if (std::chrono::steady_clock::now() - holdStarted > std::chrono::milliseconds(500))
                throw std::runtime_error("Reader redraw hold exceeded the 500ms capture-scope limit");
        } else {
            holdStarted = {};
        }
        if ((!held && !contains(clientRegion(state.hwnd), region)) ||
            MonitorFromWindow(state.hwnd, MONITOR_DEFAULTTONULL) != monitor)
            throw std::runtime_error("Reader moved outside the fixed capture region; capture stopped");
        return true;
    };
    while (std::chrono::steady_clock::now() < deadline) {
        if (!active()) { fprintf(stderr, "CAPTURE_STOP reader-left-capture-scope\n"); break; }
        DXGI_OUTDUPL_FRAME_INFO frameInfo{};
        com_ptr<IDXGIResource> resource;
        const auto result = duplication->AcquireNextFrame(100, &frameInfo, resource.put());
        if (result == DXGI_ERROR_WAIT_TIMEOUT) continue;
        check_hresult(result);
        std::vector<uint8_t> pixels;
        try {
            // AcquireNextFrame can wait for 100ms; recheck before reading any pixels.
            if (!active()) {
                fprintf(stderr, "CAPTURE_STOP reader-left-capture-scope\n");
                duplication->ReleaseFrame(); break;
            }
            if (frameInfo.LastPresentTime.QuadPart) {
                pixels = readRegion(state, resource.as<ID3D11Texture2D>().get(), box);
            }
        } catch (...) { duplication->ReleaseFrame(); throw; }
        check_hresult(duplication->ReleaseFrame());
        if (pixels.empty()) continue;
        const int64_t time = (frameInfo.LastPresentTime.QuadPart / frequency.QuadPart) * 10000000 +
            (frameInfo.LastPresentTime.QuadPart % frequency.QuadPart) * 10000000 / frequency.QuadPart;
        if (frameInfo.AccumulatedFrames > 1)
            fprintf(stderr, "CAPTURE_GAP accumulated-output-frames=%u time100ns=%lld\n", frameInfo.AccumulatedFrames, time);
        if (frameInfo.ProtectedContentMaskedOut)
            fprintf(stderr, "CAPTURE_GAP protected-content-masked time100ns=%lld\n", time);
        writeFrame(pixels, state.width, state.height, time, 0x4d445247);
    }
    return 0;
}

// Exercises the production GPU crop/readback with a synthetic texture, without reading any display.
static int testRegionCopy() {
    CaptureState state;
    check_hresult(D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_WARP, nullptr, D3D11_CREATE_DEVICE_BGRA_SUPPORT,
        nullptr, 0, D3D11_SDK_VERSION, state.device.put(), nullptr, state.context.put()));
    uint8_t pixels[5 * 4 * 4];
    for (UINT y = 0; y < 4; ++y) for (UINT x = 0; x < 5; ++x) {
        const UINT i = (y * 5 + x) * 4;
        pixels[i] = uint8_t(x); pixels[i + 1] = uint8_t(y); pixels[i + 2] = 140; pixels[i + 3] = 255;
    }
    D3D11_TEXTURE2D_DESC description{};
    description.Width = 5; description.Height = 4; description.MipLevels = 1; description.ArraySize = 1;
    description.Format = DXGI_FORMAT_B8G8R8A8_UNORM; description.SampleDesc.Count = 1;
    D3D11_SUBRESOURCE_DATA data{pixels, 5 * 4, 0};
    com_ptr<ID3D11Texture2D> texture;
    check_hresult(state.device->CreateTexture2D(&description, &data, texture.put()));
    const auto copied = readRegion(state, texture.get(), D3D11_BOX{1, 1, 0, 4, 3, 1});
    writeFrame(copied, state.width, state.height, 42, 0x4d445247);
    return 0;
}

int wmain(int argc, wchar_t** argv) {
    _setmode(_fileno(stdout), _O_BINARY);
    if (argc == 2 && wcscmp(argv[1], L"--test-region-copy") == 0) {
        try { return testRegionCopy(); } catch (...) { return 1; }
    }
    if (argc != 4 && argc != 9) return 2;
    const bool outputRegion = argc == 9 && wcscmp(argv[4], L"--output-region") == 0;
    if (argc == 9 && !outputRegion) return 2;
    const auto hwnd = reinterpret_cast<HWND>(_wcstoui64(argv[1], nullptr, 0));
    const DWORD expectedPid = wcstoul(argv[2], nullptr, 10);
    const double seconds = wcstod(argv[3], nullptr);
    if (!hwnd || !expectedPid || !std::isfinite(seconds) || seconds <= 0 || seconds > 120) return 2;
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    auto state = std::make_shared<CaptureState>();
    state->hwnd = hwnd; state->pid = expectedPid;
    auto& done = state->done;
    auto& failed = state->failed;
    try {
        init_apartment(apartment_type::multi_threaded);
        DWORD actualPid = 0, affinity = 0;
        GetWindowThreadProcessId(hwnd, &actualPid);
        if (!IsWindow(hwnd) || actualPid != expectedPid) throw std::runtime_error("Window owner mismatch");
        if (GetWindowDisplayAffinity(hwnd, &affinity) && affinity) throw std::runtime_error("Window capture policy blocks capture");
        if (outputRegion) {
            int64_t values[4];
            for (int i = 0; i < 4; ++i) {
                wchar_t* end{};
                values[i] = _wcstoi64(argv[5 + i], &end, 10);
                if (!*argv[5 + i] || *end || values[i] < INT32_MIN || values[i] > INT32_MAX)
                    throw std::runtime_error("Invalid reader client region coordinate");
            }
            if (values[2] <= 0 || values[3] <= 0 || values[0] + values[2] > INT32_MAX || values[1] + values[3] > INT32_MAX)
                throw std::runtime_error("Invalid reader client region extent");
            if (uint64_t(values[2]) * values[3] * 4 > 64 * 1024 * 1024)
                throw std::runtime_error("Reader client region exceeds the 64 MiB limit");
            const RECT region{LONG(values[0]), LONG(values[1]), LONG(values[0] + values[2]), LONG(values[1] + values[3])};
            return captureOutputRegion(*state, region, seconds);
        }

        com_ptr<ID3D11Device> device;
        com_ptr<ID3D11DeviceContext> context;
        check_hresult(D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr,
            D3D11_CREATE_DEVICE_BGRA_SUPPORT, nullptr, 0, D3D11_SDK_VERSION, device.put(), nullptr, context.put()));
        state->device = device; state->context = context;
        auto dxgi = device.as<IDXGIDevice>();
        com_ptr<::IInspectable> inspectable;
        check_hresult(CreateDirect3D11DeviceFromDXGIDevice(dxgi.get(), inspectable.put()));
        auto direct = inspectable.as<IDirect3DDevice>();
        auto factory = get_activation_factory<GraphicsCaptureItem, IGraphicsCaptureItemInterop>();
        GraphicsCaptureItem item{nullptr};
        check_hresult(factory->CreateForWindow(hwnd, guid_of<GraphicsCaptureItem>(), put_abi(item)));
        auto size = item.Size();
        MONITORINFO monitor{sizeof(MONITORINFO)};
        if (GetMonitorInfoW(MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST), &monitor)) {
            size.Width = std::max<int32_t>(size.Width, monitor.rcMonitor.right - monitor.rcMonitor.left);
            size.Height = std::max<int32_t>(size.Height, monitor.rcMonitor.bottom - monitor.rcMonitor.top);
        }
        if (size.Width <= 0 || size.Height <= 0 || uint64_t(size.Width) * size.Height * 4 > 64 * 1024 * 1024)
            throw std::runtime_error("Capture buffer exceeds the 64 MiB limit");
        // Allocate for fullscreen before entry. Window resizing does not recreate a
        // live frame pool or invalidate any borrowed/mapped buffer.
        auto pool = Direct3D11CaptureFramePool::CreateFreeThreaded(direct,
            DirectXPixelFormat::B8G8R8A8UIntNormalized, 2, size);
        auto session = pool.CreateCaptureSession(item);
        session.IsCursorCaptureEnabled(false);
        const auto closed = item.Closed([state](auto const&, auto const&) { state->done = true; });
        const auto arrived = pool.FrameArrived([state](auto const& sender, auto const&) {
            auto& done = state->done;
            auto& failed = state->failed;
            const auto hwnd = state->hwnd;
            const auto expectedPid = state->pid;
            std::lock_guard<std::mutex> guard(state->drawing);
            if (done) return;
            try {
                DWORD owner = 0;
                GetWindowThreadProcessId(hwnd, &owner);
                if (owner != expectedPid) { done = true; return; }
                auto frame = sender.TryGetNextFrame();
                if (!frame) return;
                const auto content = frame.ContentSize();
                auto access = frame.Surface().as<IDirect3DDxgiInterfaceAccess>();
                com_ptr<ID3D11Texture2D> texture;
                check_hresult(access->GetInterface(__uuidof(ID3D11Texture2D), texture.put_void()));
                D3D11_TEXTURE2D_DESC description{};
                texture->GetDesc(&description);
                if (content.Width <= 0 || content.Height <= 0) { frame.Close(); return; }
                const UINT width = static_cast<UINT>(content.Width), height = static_cast<UINT>(content.Height);
                if (width > description.Width || height > description.Height || uint64_t(width) * height * 4 > 64 * 1024 * 1024)
                    throw std::runtime_error("Content exceeded the fixed capture buffer; no clipped frame was saved");
                D3D11_BOX region{0, 0, 0, width, height, 1};
                const auto pixels = readRegion(*state, texture.get(), region);
                const auto timestamp = frame.SystemRelativeTime().count();
                frame.Close();
                writeFrame(pixels, width, height, timestamp, 0x4d574743);
            } catch (hresult_error const& error) {
                fprintf(stderr, "WGC frame error 0x%08x: %s\n", unsigned(error.code().value), to_string(error.message()).c_str());
                failed = true; done = true;
            } catch (std::exception const& error) {
                fprintf(stderr, "WGC frame error: %s\n", error.what());
                failed = true; done = true;
            }
        });
        session.StartCapture();
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(static_cast<int>(seconds * 1000));
        while (!done && std::chrono::steady_clock::now() < deadline) {
            DWORD owner = 0;
            GetWindowThreadProcessId(hwnd, &owner);
            if (owner != expectedPid) break;
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
        }
        done = true;
        pool.FrameArrived(arrived);
        item.Closed(closed);
        { std::lock_guard<std::mutex> guard(state->drawing); }
        session.Close();
        pool.Close();
        return failed ? 1 : 0;
    } catch (hresult_error const& error) {
        fprintf(stderr, "WGC error 0x%08x: %s\n", unsigned(error.code().value), to_string(error.message()).c_str());
    } catch (std::exception const& error) {
        fprintf(stderr, "WGC error: %s\n", error.what());
    }
    return 1;
}
