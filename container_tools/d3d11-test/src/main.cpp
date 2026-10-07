// Fable Direct3D 11 test: a window with a spinning, colour-cycling triangle.
//
// Bundled into every Fable container as C:\fable\tools\d3d11-test.exe (see ContainerTools.kt).
// It draws through d3d11.dll and dxgi.dll only, i.e. DXVK when the container uses it, so a
// turning triangle means Direct3D 11 works. The title bar names the adapter Direct3D reports,
// which d3d11.dll is loaded (DXVK or Wine's builtin WineD3D) and the frame rate. The shaders are
// compiled at start-up by D3DCompile from d3dcompiler_47.dll, which Wine provides. Esc or
// closing the window quits.
//
// The device and the swap chain are created in two steps (D3D11CreateDevice, then
// IDXGIFactory::CreateSwapChain) rather than with D3D11CreateDeviceAndSwapChain, so a failure
// names the layer that broke: the Direct3D device (feature level / Vulkan) or DXGI (window,
// presentation). Every error box also says which d3d11.dll and dxgi.dll are loaded, since
// Wine's builtin ones failing is a DXVK installation problem rather than a GPU one.
//
// Plain Win32 + Direct3D 11, built with MinGW-w64 (see the Makefile).

#ifndef UNICODE
#define UNICODE
#endif
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <d3d11.h>
#include <d3dcompiler.h>

#include <cstdio>
#include <cstring>
#include <cwchar>

namespace {

constexpr wchar_t kTitle[] = L"Fable Direct3D 11 Test";
constexpr wchar_t kClassName[] = L"FableD3D11Test";
constexpr LONG kWidth = 640;
constexpr LONG kHeight = 480;

// Both stages in one source. frame.x is the time in seconds and frame.y the window's
// height / width, which keeps the triangle equilateral. Each vertex carries a phase per colour
// channel; the vertex shader turns the triangle and walks every vertex through the hues.
constexpr char kShaderSource[] = R"(
cbuffer Frame : register(b0)
{
    float4 frame;
};

struct VertexOut
{
    float4 position : SV_Position;
    float4 color : COLOR;
};

VertexOut vs_main(float2 position : POSITION, float3 phase : COLOR)
{
    float s = sin(frame.x * 0.8);
    float c = cos(frame.x * 0.8);
    VertexOut result;
    result.position = float4((position.x * c - position.y * s) * frame.y,
                             position.x * s + position.y * c, 0.0, 1.0);
    result.color = float4(0.5 + 0.5 * cos(6.2831853 * (frame.x * 0.3 + phase)), 1.0);
    return result;
}

float4 ps_main(VertexOut input) : SV_Target
{
    return input.color;
}
)";

struct Vertex {
    float x, y;
    float phase[3];
};

// Clockwise (Direct3D's default front face), centred, circumradius 0.75.
constexpr Vertex kTriangle[] = {
    {0.0f, 0.75f, {0.0f, 0.333f, 0.667f}},
    {0.65f, -0.375f, {0.333f, 0.667f, 0.0f}},
    {-0.65f, -0.375f, {0.667f, 0.0f, 0.333f}},
};

HWND g_window = nullptr;
ID3D11Device* g_device = nullptr;
ID3D11DeviceContext* g_context = nullptr;
IDXGISwapChain* g_swapChain = nullptr;
ID3D11RenderTargetView* g_target = nullptr;
UINT g_width = kWidth;
UINT g_height = kHeight;
bool g_resized = false;
bool g_minimized = false;

template <typename T>
void Release(T*& object) {
    if (object) {
        object->Release();
        object = nullptr;
    }
}

// Wine marks the PE modules it builds itself with this string right after the DOS header
// (winebuild writes it; ntdll checks it to tell builtins from native DLLs). A module without it
// is a native DLL, which for d3d11.dll / dxgi.dll in a Fable container means DXVK.
const wchar_t* ModuleKind(const wchar_t* name) {
    HMODULE module = GetModuleHandleW(name);
    if (!module) return L"not loaded";
    const auto* dos = reinterpret_cast<const IMAGE_DOS_HEADER*>(module);
    static constexpr char kBuiltin[] = "Wine builtin DLL";
    if (dos->e_magic == IMAGE_DOS_SIGNATURE &&
        dos->e_lfanew >= static_cast<LONG>(sizeof(IMAGE_DOS_HEADER) + sizeof(kBuiltin)) &&
        std::memcmp(reinterpret_cast<const char*>(module) + sizeof(IMAGE_DOS_HEADER), kBuiltin, sizeof(kBuiltin)) == 0) {
        return L"Wine builtin";
    }
    return L"native (DXVK)";
}

// Names for the HRESULTs Direct3D / DXGI set-up fails with, so the box is readable without a
// lookup. 0x887A0001 in particular is INVALID_CALL, not UNSUPPORTED.
const wchar_t* HResultName(HRESULT hr) {
    switch (static_cast<unsigned long>(hr)) {
    case 0x80004005UL: return L"E_FAIL";
    case 0x80070057UL: return L"E_INVALIDARG";
    case 0x8007000EUL: return L"E_OUTOFMEMORY";
    case 0x80004001UL: return L"E_NOTIMPL";
    case 0x887A0001UL: return L"DXGI_ERROR_INVALID_CALL";
    case 0x887A0002UL: return L"DXGI_ERROR_NOT_FOUND";
    case 0x887A0004UL: return L"DXGI_ERROR_UNSUPPORTED";
    case 0x887A0005UL: return L"DXGI_ERROR_DEVICE_REMOVED";
    case 0x887A0006UL: return L"DXGI_ERROR_DEVICE_HUNG";
    case 0x887A0007UL: return L"DXGI_ERROR_DEVICE_RESET";
    case 0x887A0020UL: return L"DXGI_ERROR_DRIVER_INTERNAL_ERROR";
    case 0x887A0022UL: return L"DXGI_ERROR_NOT_CURRENTLY_AVAILABLE";
    default: return L"";
    }
}

// Error box naming the step that failed (which tells which layer is broken), the HRESULT by
// name and number, and which d3d11.dll / dxgi.dll served the call.
int Fail(const wchar_t* what, HRESULT hr) {
    wchar_t text[768];
    const wchar_t* name = HResultName(hr);
    std::swprintf(text, 768, L"%ls failed (HRESULT 0x%08lX%ls%ls).\n\nd3d11.dll: %ls\ndxgi.dll: %ls", what,
                  static_cast<unsigned long>(hr), name[0] ? L" " : L"", name, ModuleKind(L"d3d11.dll"),
                  ModuleKind(L"dxgi.dll"));
    MessageBoxW(g_window, text, kTitle, MB_OK | MB_ICONERROR);
    return 1;
}

// Compiles one entry point of kShaderSource; on failure shows the compiler's message.
ID3DBlob* Compile(pD3DCompile compile, const char* entry, const char* target) {
    ID3DBlob* code = nullptr;
    ID3DBlob* errors = nullptr;
    HRESULT hr = compile(kShaderSource, sizeof(kShaderSource) - 1, "d3d11-test.hlsl", nullptr, nullptr,
                         entry, target, 0, 0, &code, &errors);
    if (FAILED(hr)) {
        char text[2048];
        std::snprintf(text, sizeof(text), "Compiling %s failed (HRESULT 0x%08lX):\n\n%.*s", entry,
                      static_cast<unsigned long>(hr),
                      errors ? static_cast<int>(errors->GetBufferSize()) : 0,
                      errors ? static_cast<const char*>(errors->GetBufferPointer()) : "");
        MessageBoxA(g_window, text, "Fable Direct3D 11 Test", MB_OK | MB_ICONERROR);
        Release(code);
    }
    Release(errors);
    return code;
}

// (Re)creates the render target view of the swap chain's back buffer.
HRESULT CreateTarget() {
    ID3D11Texture2D* backBuffer = nullptr;
    HRESULT hr = g_swapChain->GetBuffer(0, IID_PPV_ARGS(&backBuffer));
    if (SUCCEEDED(hr)) {
        hr = g_device->CreateRenderTargetView(backBuffer, nullptr, &g_target);
        backBuffer->Release();
    }
    return hr;
}

LRESULT CALLBACK WindowProc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    switch (message) {
    case WM_SIZE:
        g_minimized = wparam == SIZE_MINIMIZED;
        if (!g_minimized && LOWORD(lparam) > 0 && HIWORD(lparam) > 0) {
            g_width = LOWORD(lparam);
            g_height = HIWORD(lparam);
            g_resized = true;
        }
        return 0;
    case WM_KEYDOWN:
        if (wparam == VK_ESCAPE) DestroyWindow(window);
        return 0;
    case WM_DESTROY:
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(window, message, wparam, lparam);
}

}  // namespace

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int show) {
    WNDCLASSEXW windowClass = {};
    windowClass.cbSize = sizeof(windowClass);
    windowClass.style = CS_HREDRAW | CS_VREDRAW;
    windowClass.lpfnWndProc = WindowProc;
    windowClass.hInstance = instance;
    windowClass.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    windowClass.lpszClassName = kClassName;
    if (!RegisterClassExW(&windowClass)) return Fail(L"RegisterClassEx", HRESULT_FROM_WIN32(GetLastError()));

    // A normal window with a 640x480 client area, centred on the (virtual) desktop.
    RECT frame = {0, 0, kWidth, kHeight};
    AdjustWindowRect(&frame, WS_OVERLAPPEDWINDOW, FALSE);
    const int width = frame.right - frame.left;
    const int height = frame.bottom - frame.top;
    const int x = (GetSystemMetrics(SM_CXSCREEN) - width) / 2;
    const int y = (GetSystemMetrics(SM_CYSCREEN) - height) / 2;
    g_window = CreateWindowExW(0, kClassName, kTitle, WS_OVERLAPPEDWINDOW, x > 0 ? x : 0, y > 0 ? y : 0,
                               width, height, nullptr, nullptr, instance, nullptr);
    if (!g_window) return Fail(L"CreateWindowEx", HRESULT_FROM_WIN32(GetLastError()));
    ShowWindow(g_window, show);

    // Shaders first: a failure here is the HLSL compiler's, not Direct3D's.
    HMODULE compiler = LoadLibraryW(D3DCOMPILER_DLL_W);
    if (!compiler) return Fail(L"Loading " D3DCOMPILER_DLL_W, HRESULT_FROM_WIN32(GetLastError()));
    auto compile = reinterpret_cast<pD3DCompile>(reinterpret_cast<void*>(GetProcAddress(compiler, "D3DCompile")));
    if (!compile) return Fail(L"Finding D3DCompile", HRESULT_FROM_WIN32(GetLastError()));
    ID3DBlob* vertexCode = Compile(compile, "vs_main", "vs_4_0");
    if (!vertexCode) return 1;
    ID3DBlob* pixelCode = Compile(compile, "ps_main", "ps_4_0");
    if (!pixelCode) return 1;

    // The device first. The runtime takes the highest level in the list the GPU supports and
    // fails only when none is (vs_4_0 / ps_4_0 shaders need at least 10_0), so a failure here is
    // the Direct3D / Vulkan layer: DXVK reports E_INVALIDARG for an unsupported feature level.
    const D3D_FEATURE_LEVEL levels[] = {D3D_FEATURE_LEVEL_11_0, D3D_FEATURE_LEVEL_10_1, D3D_FEATURE_LEVEL_10_0};
    D3D_FEATURE_LEVEL level = D3D_FEATURE_LEVEL_10_0;
    HRESULT hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, 0, levels, ARRAYSIZE(levels),
                                   D3D11_SDK_VERSION, &g_device, &level, &g_context);
    if (FAILED(hr)) return Fail(L"D3D11CreateDevice (feature levels 11_0, 10_1, 10_0)", hr);

    // The device's adapter and DXGI factory: the adapter's name goes in the title bar, the
    // factory makes the swap chain.
    wchar_t adapterName[128] = L"unknown adapter";
    IDXGIDevice* dxgiDevice = nullptr;
    IDXGIAdapter* adapter = nullptr;
    IDXGIFactory* factory = nullptr;
    hr = g_device->QueryInterface(IID_PPV_ARGS(&dxgiDevice));
    if (FAILED(hr)) return Fail(L"QueryInterface(IDXGIDevice)", hr);
    hr = dxgiDevice->GetAdapter(&adapter);
    if (FAILED(hr)) return Fail(L"IDXGIDevice::GetAdapter", hr);
    DXGI_ADAPTER_DESC adapterDesc = {};
    if (SUCCEEDED(adapter->GetDesc(&adapterDesc))) std::wcsncpy(adapterName, adapterDesc.Description, 127);
    hr = adapter->GetParent(IID_PPV_ARGS(&factory));
    if (FAILED(hr)) return Fail(L"IDXGIAdapter::GetParent(IDXGIFactory)", hr);

    // Windowed, legacy blit model: the most widely supported swap chain. A failure here is
    // DXGI's: the window, the monitor it is on, or presentation to the X server.
    DXGI_SWAP_CHAIN_DESC swapChainDesc = {};
    swapChainDesc.BufferDesc.Width = g_width;
    swapChainDesc.BufferDesc.Height = g_height;
    swapChainDesc.BufferDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    swapChainDesc.SampleDesc.Count = 1;
    swapChainDesc.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    swapChainDesc.BufferCount = 1;
    swapChainDesc.OutputWindow = g_window;
    swapChainDesc.Windowed = TRUE;
    swapChainDesc.SwapEffect = DXGI_SWAP_EFFECT_DISCARD;
    hr = factory->CreateSwapChain(g_device, &swapChainDesc, &g_swapChain);
    if (FAILED(hr)) return Fail(L"IDXGIFactory::CreateSwapChain", hr);
    // No Alt+Enter: the test stays windowed.
    factory->MakeWindowAssociation(g_window, DXGI_MWA_NO_ALT_ENTER);
    Release(factory);
    Release(adapter);
    Release(dxgiDevice);
    hr = CreateTarget();
    if (FAILED(hr)) return Fail(L"CreateRenderTargetView", hr);
    const wchar_t* d3d11Kind = ModuleKind(L"d3d11.dll");

    ID3D11VertexShader* vertexShader = nullptr;
    ID3D11PixelShader* pixelShader = nullptr;
    ID3D11InputLayout* inputLayout = nullptr;
    ID3D11Buffer* vertexBuffer = nullptr;
    ID3D11Buffer* constants = nullptr;
    hr = g_device->CreateVertexShader(vertexCode->GetBufferPointer(), vertexCode->GetBufferSize(), nullptr, &vertexShader);
    if (FAILED(hr)) return Fail(L"CreateVertexShader", hr);
    hr = g_device->CreatePixelShader(pixelCode->GetBufferPointer(), pixelCode->GetBufferSize(), nullptr, &pixelShader);
    if (FAILED(hr)) return Fail(L"CreatePixelShader", hr);
    const D3D11_INPUT_ELEMENT_DESC inputDesc[] = {
        {"POSITION", 0, DXGI_FORMAT_R32G32_FLOAT, 0, 0, D3D11_INPUT_PER_VERTEX_DATA, 0},
        {"COLOR", 0, DXGI_FORMAT_R32G32B32_FLOAT, 0, 8, D3D11_INPUT_PER_VERTEX_DATA, 0},
    };
    hr = g_device->CreateInputLayout(inputDesc, ARRAYSIZE(inputDesc), vertexCode->GetBufferPointer(),
                                     vertexCode->GetBufferSize(), &inputLayout);
    if (FAILED(hr)) return Fail(L"CreateInputLayout", hr);
    Release(vertexCode);
    Release(pixelCode);

    D3D11_BUFFER_DESC vertexDesc = {};
    vertexDesc.ByteWidth = sizeof(kTriangle);
    vertexDesc.Usage = D3D11_USAGE_IMMUTABLE;
    vertexDesc.BindFlags = D3D11_BIND_VERTEX_BUFFER;
    D3D11_SUBRESOURCE_DATA vertexData = {};
    vertexData.pSysMem = kTriangle;
    hr = g_device->CreateBuffer(&vertexDesc, &vertexData, &vertexBuffer);
    if (FAILED(hr)) return Fail(L"CreateBuffer (vertices)", hr);
    D3D11_BUFFER_DESC constantsDesc = {};
    constantsDesc.ByteWidth = 16;  // one float4
    constantsDesc.Usage = D3D11_USAGE_DEFAULT;
    constantsDesc.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
    hr = g_device->CreateBuffer(&constantsDesc, nullptr, &constants);
    if (FAILED(hr)) return Fail(L"CreateBuffer (constants)", hr);

    const UINT stride = sizeof(Vertex);
    const UINT offset = 0;
    g_context->IASetInputLayout(inputLayout);
    g_context->IASetVertexBuffers(0, 1, &vertexBuffer, &stride, &offset);
    g_context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    g_context->VSSetShader(vertexShader, nullptr, 0);
    g_context->VSSetConstantBuffers(0, 1, &constants);
    g_context->PSSetShader(pixelShader, nullptr, 0);

    LARGE_INTEGER frequency, start, now, lastTitle;
    QueryPerformanceFrequency(&frequency);
    QueryPerformanceCounter(&start);
    lastTitle = start;
    unsigned frames = 0;

    MSG message = {};
    while (message.message != WM_QUIT) {
        if (PeekMessageW(&message, nullptr, 0, 0, PM_REMOVE)) {
            TranslateMessage(&message);
            DispatchMessageW(&message);
            continue;
        }
        if (g_minimized) {
            WaitMessage();
            continue;
        }
        if (g_resized) {
            // ResizeBuffers needs every view of the old back buffer unbound and released.
            g_resized = false;
            g_context->OMSetRenderTargets(0, nullptr, nullptr);
            Release(g_target);
            hr = g_swapChain->ResizeBuffers(0, 0, 0, DXGI_FORMAT_UNKNOWN, 0);
            if (SUCCEEDED(hr)) hr = CreateTarget();
            if (FAILED(hr)) return Fail(L"ResizeBuffers", hr);
        }

        QueryPerformanceCounter(&now);
        const float frameConstants[4] = {
            static_cast<float>(static_cast<double>(now.QuadPart - start.QuadPart) / frequency.QuadPart),
            static_cast<float>(g_height) / static_cast<float>(g_width), 0.0f, 0.0f,
        };
        g_context->UpdateSubresource(constants, 0, nullptr, frameConstants, 0, 0);
        const D3D11_VIEWPORT viewport = {0.0f, 0.0f, static_cast<float>(g_width), static_cast<float>(g_height), 0.0f, 1.0f};
        g_context->RSSetViewports(1, &viewport);
        g_context->OMSetRenderTargets(1, &g_target, nullptr);
        const float background[4] = {0.07f, 0.07f, 0.08f, 1.0f};
        g_context->ClearRenderTargetView(g_target, background);
        g_context->Draw(3, 0);
        hr = g_swapChain->Present(1, 0);
        if (hr == DXGI_ERROR_DEVICE_REMOVED || hr == DXGI_ERROR_DEVICE_RESET) {
            return Fail(L"Present (device lost)", g_device->GetDeviceRemovedReason());
        }

        ++frames;
        if (now.QuadPart - lastTitle.QuadPart >= frequency.QuadPart) {
            wchar_t title[320];
            std::swprintf(title, 320, L"%ls - %ls - feature level %u_%u - d3d11 %ls - %u fps", kTitle, adapterName,
                          (static_cast<unsigned>(level) >> 12) & 0xFu, (static_cast<unsigned>(level) >> 8) & 0xFu,
                          d3d11Kind, frames);
            SetWindowTextW(g_window, title);
            frames = 0;
            lastTitle = now;
        }
    }

    g_context->ClearState();
    Release(constants);
    Release(vertexBuffer);
    Release(inputLayout);
    Release(pixelShader);
    Release(vertexShader);
    Release(g_target);
    Release(g_swapChain);
    Release(g_context);
    Release(g_device);
    return static_cast<int>(message.wParam);
}
