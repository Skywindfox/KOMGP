/*
 * KOMGP 的 Windows 原生启动器（不弹控制台窗口）。
 *
 * 职责：找到 Java、拼出 classpath、用 javaw 拉起游戏本体。
 * 找不到 Java 时弹一个说人话的对话框，而不是闪退。
 */
#define UNICODE
#define _UNICODE
#include <windows.h>
#include <shellapi.h>
#include <stdio.h>
#include <wchar.h>

static int file_exists(const wchar_t *path) {
    DWORD a = GetFileAttributesW(path);
    return a != INVALID_FILE_ATTRIBUTES && !(a & FILE_ATTRIBUTE_DIRECTORY);
}

/* 在注册表里找已安装的 JRE/JDK，返回 0 表示没找到 */
static int java_from_registry(wchar_t *out, DWORD cap) {
    static const wchar_t *roots[] = {
        L"SOFTWARE\\JavaSoft\\Java Runtime Environment",
        L"SOFTWARE\\JavaSoft\\Java Development Kit",
        L"SOFTWARE\\JavaSoft\\JRE",
        L"SOFTWARE\\JavaSoft\\JDK",
        L"SOFTWARE\\Wow6432Node\\JavaSoft\\Java Runtime Environment",
        L"SOFTWARE\\Wow6432Node\\JavaSoft\\Java Development Kit",
    };
    for (int i = 0; i < (int)(sizeof(roots) / sizeof(roots[0])); i++) {
        HKEY root;
        if (RegOpenKeyExW(HKEY_LOCAL_MACHINE, roots[i], 0, KEY_READ, &root) != ERROR_SUCCESS)
            continue;
        /* 枚举版本子键，取名字最大的（字典序近似版本序） */
        wchar_t best[64] = {0};
        wchar_t name[64];
        DWORD index = 0, len = 64;
        while (RegEnumKeyExW(root, index++, name, &len, NULL, NULL, NULL, NULL) == ERROR_SUCCESS) {
            if (wcscmp(name, best) > 0) wcscpy(best, name);
            len = 64;
        }
        int found = 0;
        if (best[0]) {
            HKEY ver;
            if (RegOpenKeyExW(root, best, 0, KEY_READ, &ver) == ERROR_SUCCESS) {
                wchar_t home[MAX_PATH];
                DWORD sz = sizeof(home);
                DWORD type = 0;
                if (RegQueryValueExW(ver, L"JavaHome", NULL, &type, (LPBYTE)home, &sz) == ERROR_SUCCESS
                        && type == REG_SZ) {
                    wchar_t exe[MAX_PATH];
                    _snwprintf(exe, MAX_PATH, L"%s\\bin\\javaw.exe", home);
                    if (file_exists(exe)) {
                        wcsncpy(out, exe, cap - 1);
                        out[cap - 1] = 0;
                        found = 1;
                    }
                }
                RegCloseKey(ver);
            }
        }
        RegCloseKey(root);
        if (found) return 1;
    }
    return 0;
}

typedef struct {
    wchar_t java[MAX_PATH];
    wchar_t dir[MAX_PATH];
} Ctx;

int WINAPI wWinMain(HINSTANCE hInst, HINSTANCE hPrev, PWSTR pCmdLine, int nCmdShow) {
    (void)hInst;
    (void)hPrev;
    (void)pCmdLine;
    (void)nCmdShow;
    int argc = 0;
    wchar_t **argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    Ctx ctx;
    if (!GetModuleFileNameW(NULL, ctx.dir, MAX_PATH)) return 2;

    /* 取 exe 所在目录 */
    wchar_t *slash = wcsrchr(ctx.dir, L'\\');
    if (slash) *slash = 0;
    else wcscpy(ctx.dir, L".");

    ctx.java[0] = 0;

    /* 1) 便携 JRE：<exe目录>\runtime\bin\javaw.exe */
    {
        wchar_t p[MAX_PATH];
        _snwprintf(p, MAX_PATH, L"%s\\runtime\\bin\\javaw.exe", ctx.dir);
        if (file_exists(p)) wcscpy(ctx.java, p);
    }
    /* 2) JAVA_HOME */
    if (!ctx.java[0]) {
        wchar_t home[MAX_PATH];
        DWORD n = GetEnvironmentVariableW(L"JAVA_HOME", home, MAX_PATH);
        if (n > 0 && n < MAX_PATH) {
            wchar_t p[MAX_PATH];
            _snwprintf(p, MAX_PATH, L"%s\\bin\\javaw.exe", home);
            if (file_exists(p)) wcscpy(ctx.java, p);
        }
    }
    /* 3) 注册表 */
    if (!ctx.java[0]) java_from_registry(ctx.java, MAX_PATH);
    /* 4) PATH */
    if (!ctx.java[0]) {
        wchar_t found[MAX_PATH];
        if (SearchPathW(NULL, L"javaw.exe", NULL, MAX_PATH, found, NULL)) {
            wcscpy(ctx.java, found);
        }
    }

    wchar_t jar[MAX_PATH];
    _snwprintf(jar, MAX_PATH, L"%s\\KOMGP.jar", ctx.dir);
    if (!file_exists(jar)) {
        MessageBoxW(NULL, L"KOMGP.jar not found next to this program.\n"
                          L"Please keep the whole folder together.\n\n"
                          L"(KOMGP.jar \u5e94\u4e0e\u672c\u7a0b\u5e8f\u5728\u540c\u4e00\u76ee\u5f55)",
                    L"KOMGP - missing files", MB_ICONERROR | MB_OK);
        return 3;
    }
    if (!ctx.java[0]) {
        MessageBoxW(NULL,
                    L"Java 17 or newer was not found.\n\n"
                    L"Install a JRE/JDK 17+ (https://adoptium.net) and run this again,\n"
                    L"or set JAVA_HOME, or put a portable runtime in the 'runtime' folder.\n\n"
                    L"\u672a\u627e\u5230 Java 17\uff0c\u8bf7\u5b89\u88c5\u540e\u91cd\u8bd5\u3002",
                    L"KOMGP - Java not found", MB_ICONERROR | MB_OK);
        return 4;
    }

    /* 拼参数：先放我们自己的，再追加用户传入的（从命令行启动时有用） */
    static wchar_t cmd[32768];
    int off = _snwprintf(cmd, 32768,
        L"\"%s\" -Xms256m -Xmx512m -XX:+UseSerialGC -Dfile.encoding=UTF-8 "
        L"-cp \"%s\\KOMGP.jar;%s\\lib\\*\" komgp.Main",
        ctx.java, ctx.dir, ctx.dir);
    for (int i = 1; i < argc && off > 0 && off < 30000; i++) {
        off += _snwprintf(cmd + off, 32768 - off, L" \"%s\"", argv[i]);
    }

    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    si.cb = sizeof(si);
    ZeroMemory(&pi, sizeof(pi));
    if (!CreateProcessW(ctx.java, cmd, NULL, NULL, FALSE, 0, NULL, ctx.dir, &si, &pi)) {
        wchar_t msg[512];
        _snwprintf(msg, 512, L"Failed to start Java (error %lu).\n\u65e0\u6cd5\u542f\u52a8 Java\uff08\u9519\u8bef\u7801 %lu\uff09",
                   GetLastError(), GetLastError());
        MessageBoxW(NULL, msg, L"KOMGP", MB_ICONERROR | MB_OK);
        return 5;
    }
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    if (argv) LocalFree(argv);
    return 0;
}
