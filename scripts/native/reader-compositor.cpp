// Window-scoped WGC diagnostics. No desktop duplication, input, or window changes.
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <d3d11.h>
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

int wmain(int argc, wchar_t** argv) {
    if (argc != 4) return 2;
    const auto hwnd = reinterpret_cast<HWND>(_wcstoui64(argv[1], nullptr, 0));
    const DWORD expectedPid = wcstoul(argv[2], nullptr, 10);
    const double seconds = wcstod(argv[3], nullptr);
    if (!hwnd || !expectedPid || !std::isfinite(seconds) || seconds <= 0 || seconds > 120) return 2;
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    _setmode(_fileno(stdout), _O_BINARY);
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
            auto& device = state->device;
            auto& context = state->context;
            auto& staging = state->staging;
            auto& stagingWidth = state->width;
            auto& stagingHeight = state->height;
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
                if (!staging || width != stagingWidth || height != stagingHeight) {
                    D3D11_TEXTURE2D_DESC target{};
                    target.Width = width; target.Height = height;
                    target.MipLevels = 1; target.ArraySize = 1;
                    target.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
                    target.SampleDesc.Count = 1; target.Usage = D3D11_USAGE_STAGING;
                    target.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
                    staging = nullptr;
                    check_hresult(device->CreateTexture2D(&target, nullptr, staging.put()));
                    stagingWidth = width; stagingHeight = height;
                }
                D3D11_BOX region{0, 0, 0, width, height, 1};
                context->CopySubresourceRegion(staging.get(), 0, 0, 0, 0, texture.get(), 0, &region);
                std::vector<uint8_t> pixels(size_t(width) * height * 4);
                D3D11_MAPPED_SUBRESOURCE mapped{};
                check_hresult(context->Map(staging.get(), 0, D3D11_MAP_READ, 0, &mapped));
                if (mapped.RowPitch < width * 4) {
                    context->Unmap(staging.get(), 0);
                    throw std::runtime_error("Mapped row pitch was smaller than the content width");
                }
                for (UINT row = 0; row < height; ++row)
                    memcpy(pixels.data() + size_t(row) * width * 4,
                        static_cast<uint8_t*>(mapped.pData) + size_t(row) * mapped.RowPitch, size_t(width) * 4);
                context->Unmap(staging.get(), 0);
                const Header header{0x4d574743, width, height, width, height, frame.SystemRelativeTime().count()};
                frame.Close();
                if (fwrite(&header, sizeof(header), 1, stdout) != 1 || fwrite(pixels.data(), pixels.size(), 1, stdout) != 1 || fflush(stdout))
                    throw std::runtime_error("Capture pipe closed");
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
