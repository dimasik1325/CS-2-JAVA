/*
 * CS-2 JAVA 10.0 — Native Windows 10/11 x64 Launcher (cs2_launcher.c)
 *
 * Features:
 *  - Pure Win32 Wide-Unicode (UTF-16) GUI executable (zero black console window,
 *    full support for Cyrillic / spaces in Windows user & folder paths).
 *  - Self-contained: embeds CS-2-JAVA.jar directly inside the .exe binary so
 *    CS-2-JAVA.exe works as a single standalone file anywhere on Windows 10/11.
 *  - Smart 6-tier Java 11+ (64-bit) discovery (bundled jre/, %LOCALAPPDATA% cache,
 *    JAVA_HOME, PATH, Program Files JDK/JRE vendors, and Windows 10/11 gaming runtimes),
 *    verifying Java major version >= 11 so legacy Java 8 stubs never crash the launch.
 *  - Zero-Admin 1-Click Portable OpenJDK 21 JRE Auto-Setup if Java 11+ is not installed.
 */

#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#define WIN32_LEAN_AND_MEAN

#include <windows.h>
#include <shellapi.h>
#include <wchar.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MAX_WPATH 1024
#define IDR_EMBEDDED_JAR 101

static void get_parent_dir(const wchar_t *full_path, wchar_t *out_dir, size_t cap) {
    wcsncpy(out_dir, full_path, cap - 1);
    out_dir[cap - 1] = L'\0';
    wchar_t *last_sep = wcsrchr(out_dir, L'\\');
    wchar_t *last_fwd = wcsrchr(out_dir, L'/');
    if (last_fwd && (!last_sep || last_fwd > last_sep)) {
        last_sep = last_fwd;
    }
    if (last_sep) {
        *last_sep = L'\0';
    }
}

static bool file_exists_w(const wchar_t *path) {
    DWORD attr = GetFileAttributesW(path);
    return (attr != INVALID_FILE_ATTRIBUTES) && !(attr & FILE_ATTRIBUTE_DIRECTORY);
}

static bool dir_exists_w(const wchar_t *path) {
    DWORD attr = GetFileAttributesW(path);
    return (attr != INVALID_FILE_ATTRIBUTES) && (attr & FILE_ATTRIBUTE_DIRECTORY);
}

static uint64_t get_file_size_w(const wchar_t *path) {
    WIN32_FILE_ATTRIBUTE_DATA fad;
    if (!GetFileAttributesExW(path, GetFileExInfoStandard, &fad)) {
        return 0;
    }
    return ((uint64_t)fad.nFileSizeHigh << 32) | (uint64_t)fad.nFileSizeLow;
}

static void ensure_dir_tree_w(const wchar_t *dir_path) {
    wchar_t tmp[MAX_WPATH];
    wcsncpy(tmp, dir_path, MAX_WPATH - 1);
    tmp[MAX_WPATH - 1] = L'\0';
    for (wchar_t *p = tmp + 3; *p; p++) {
        if (*p == L'\\' || *p == L'/') {
            wchar_t saved = *p;
            *p = L'\0';
            CreateDirectoryW(tmp, NULL);
            *p = saved;
        }
    }
    CreateDirectoryW(tmp, NULL);
}

static bool is_dir_writable_w(const wchar_t *dir_path) {
    wchar_t probe[MAX_WPATH];
    _snwprintf(probe, MAX_WPATH, L"%s\\.cs2_write_test_%lu.tmp", dir_path, (unsigned long)GetCurrentProcessId());
    probe[MAX_WPATH - 1] = L'\0';
    HANDLE h = CreateFileW(probe, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS,
                           FILE_ATTRIBUTE_TEMPORARY | FILE_FLAG_DELETE_ON_CLOSE, NULL);
    if (h == INVALID_HANDLE_VALUE) {
        return false;
    }
    CloseHandle(h);
    return true;
}

static bool extract_jar_if_needed(const wchar_t *exe_dir, const wchar_t *app_dir, wchar_t *out_jar, size_t cap) {
    HRSRC hRes = FindResourceW(NULL, MAKEINTRESOURCEW(IDR_EMBEDDED_JAR), RT_RCDATA);
    if (!hRes) return false;
    DWORD jar_len = SizeofResource(NULL, hRes);
    HGLOBAL hMem = LoadResource(NULL, hRes);
    const unsigned char *jar_data = hMem ? (const unsigned char *)LockResource(hMem) : NULL;
    if (!jar_data || jar_len < 1024) return false;

    wchar_t local_jar[MAX_WPATH];
    _snwprintf(local_jar, MAX_WPATH, L"%s\\CS-2-JAVA.jar", exe_dir);
    local_jar[MAX_WPATH - 1] = L'\0';

    if (file_exists_w(local_jar) && get_file_size_w(local_jar) == (uint64_t)jar_len) {
        wcsncpy(out_jar, local_jar, cap - 1);
        out_jar[cap - 1] = L'\0';
        return true;
    }

    ensure_dir_tree_w(app_dir);
    _snwprintf(out_jar, cap, L"%s\\CS-2-JAVA-10.0.jar", app_dir);
    out_jar[cap - 1] = L'\0';

    if (file_exists_w(out_jar) && get_file_size_w(out_jar) == (uint64_t)jar_len) {
        HANDLE hc = CreateFileW(out_jar, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, 0, NULL);
        if (hc != INVALID_HANDLE_VALUE) {
            unsigned char head[32];
            DWORD got = 0;
            bool match = ReadFile(hc, head, sizeof(head), &got, NULL)
                      && got == sizeof(head)
                      && memcmp(head, jar_data, sizeof(head)) == 0;
            CloseHandle(hc);
            if (match) {
                return true;
            }
        }
    }

    HANDLE hf = CreateFileW(out_jar, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (hf == INVALID_HANDLE_VALUE) {
        return false;
    }
    DWORD offset = 0;
    while (offset < jar_len) {
        DWORD chunk = jar_len - offset;
        if (chunk > 65536) chunk = 65536;
        DWORD written = 0;
        if (!WriteFile(hf, jar_data + offset, chunk, &written, NULL) || written == 0) {
            CloseHandle(hf);
            return false;
        }
        offset += written;
    }
    CloseHandle(hf);
    return true;
}

/*
 * Verifies that candidate javaw.exe belongs to a working Java 11+ installation.
 * Returns major version (e.g. 11, 17, 21, 25) or 0 if invalid / Java <= 8.
 */
static int probe_java_major_version(const wchar_t *javaw_path) {
    if (!file_exists_w(javaw_path)) {
        return 0;
    }

    wchar_t java_con[MAX_WPATH];
    wcsncpy(java_con, javaw_path, MAX_WPATH - 1);
    java_con[MAX_WPATH - 1] = L'\0';

    size_t len = wcslen(java_con);
    if (len > 9 && _wcsicmp(java_con + len - 9, L"javaw.exe") == 0) {
        wcscpy(java_con + len - 9, L"java.exe");
    }
    if (!file_exists_w(java_con)) {
        return 11; /* JRE stripped of java.exe: accept javaw.exe */
    }

    SECURITY_ATTRIBUTES sa;
    ZeroMemory(&sa, sizeof(sa));
    sa.nLength = sizeof(sa);
    sa.bInheritHandle = TRUE;

    HANDLE hRead = NULL, hWrite = NULL;
    if (!CreatePipe(&hRead, &hWrite, &sa, 0)) {
        return 0;
    }
    SetHandleInformation(hRead, HANDLE_FLAG_INHERIT, 0);

    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    ZeroMemory(&pi, sizeof(pi));
    si.cb = sizeof(si);
    si.dwFlags = STARTF_USESTDHANDLES | STARTF_USESHOWWINDOW;
    si.wShowWindow = SW_HIDE;
    si.hStdOutput = hWrite;
    si.hStdError = hWrite;
    si.hStdInput = GetStdHandle(STD_INPUT_HANDLE);

    wchar_t cmd[MAX_WPATH + 32];
    _snwprintf(cmd, MAX_WPATH + 32, L"\"%s\" -version", java_con);
    cmd[MAX_WPATH + 31] = L'\0';

    BOOL ok = CreateProcessW(java_con, cmd, NULL, NULL, TRUE, CREATE_NO_WINDOW, NULL, NULL, &si, &pi);
    CloseHandle(hWrite);
    if (!ok) {
        CloseHandle(hRead);
        return 0;
    }

    char buf[1024];
    DWORD total = 0;
    DWORD waitRes = WaitForSingleObject(pi.hProcess, 3500);
    if (waitRes == WAIT_TIMEOUT) {
        TerminateProcess(pi.hProcess, 1);
    }
    for (;;) {
        DWORD avail = 0;
        if (!PeekNamedPipe(hRead, NULL, 0, NULL, &avail, NULL) || avail == 0 || total >= sizeof(buf) - 1) {
            break;
        }
        DWORD got = 0;
        if (!ReadFile(hRead, buf + total, (DWORD)(sizeof(buf) - 1 - total), &got, NULL) || got == 0) {
            break;
        }
        total += got;
    }
    buf[total] = '\0';

    WaitForSingleObject(pi.hProcess, 1000);
    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);
    CloseHandle(hRead);

    const char *vp = strstr(buf, "version \"");
    if (!vp) {
        return 0;
    }
    vp += 9;
    if (vp[0] == '1' && vp[1] == '.') {
        return atoi(vp + 2); /* e.g. 1.8.0 -> 8 */
    }
    return atoi(vp); /* e.g. 17.0.12 -> 17, 21.0.5 -> 21 */
}

static void check_candidate(const wchar_t *cand_javaw, int *best_ver, wchar_t *best_path, size_t cap) {
    int ver = probe_java_major_version(cand_javaw);
    if (ver >= 11 && ver > *best_ver) {
        *best_ver = ver;
        wcsncpy(best_path, cand_javaw, cap - 1);
        best_path[cap - 1] = L'\0';
    }
}

static void scan_vendor_root(const wchar_t *vendor_root, int *best_ver, wchar_t *best_path, size_t cap) {
    if (!dir_exists_w(vendor_root)) return;

    wchar_t pattern[MAX_WPATH];
    _snwprintf(pattern, MAX_WPATH, L"%s\\*", vendor_root);
    pattern[MAX_WPATH - 1] = L'\0';

    WIN32_FIND_DATAW fd;
    HANDLE hFind = FindFirstFileW(pattern, &fd);
    if (hFind == INVALID_HANDLE_VALUE) return;

    do {
        if ((fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)
            && wcscmp(fd.cFileName, L".") != 0
            && wcscmp(fd.cFileName, L"..") != 0) {
            wchar_t cand[MAX_WPATH];
            _snwprintf(cand, MAX_WPATH, L"%s\\%s\\bin\\javaw.exe", vendor_root, fd.cFileName);
            cand[MAX_WPATH - 1] = L'\0';
            check_candidate(cand, best_ver, best_path, cap);

            _snwprintf(cand, MAX_WPATH, L"%s\\%s\\jre\\bin\\javaw.exe", vendor_root, fd.cFileName);
            cand[MAX_WPATH - 1] = L'\0';
            check_candidate(cand, best_ver, best_path, cap);
        }
    } while (FindNextFileW(hFind, &fd));
    FindClose(hFind);
}

static bool find_java11_runtime(const wchar_t *exe_dir, const wchar_t *app_dir, wchar_t *out_javaw, size_t cap) {
    int best_ver = 0;
    out_javaw[0] = L'\0';

    /* 1. Bundled portable JRE next to CS-2-JAVA.exe */
    const wchar_t *local_rel[] = { L"jre\\bin\\javaw.exe", L"runtime\\bin\\javaw.exe", L"jdk\\bin\\javaw.exe" };
    for (size_t i = 0; i < 3; i++) {
        wchar_t cand[MAX_WPATH];
        _snwprintf(cand, MAX_WPATH, L"%s\\%s", exe_dir, local_rel[i]);
        cand[MAX_WPATH - 1] = L'\0';
        check_candidate(cand, &best_ver, out_javaw, cap);
        if (best_ver >= 11) return true;
    }

    wchar_t sub_root[MAX_WPATH];
    _snwprintf(sub_root, MAX_WPATH, L"%s\\jre", exe_dir);
    scan_vendor_root(sub_root, &best_ver, out_javaw, cap);
    if (best_ver >= 11) return true;

    /* 2. Cached portable OpenJDK 21 in %LOCALAPPDATA%\CS-2-JAVA\jre */
    _snwprintf(sub_root, MAX_WPATH, L"%s\\jre\\bin\\javaw.exe", app_dir);
    check_candidate(sub_root, &best_ver, out_javaw, cap);
    if (best_ver >= 11) return true;

    _snwprintf(sub_root, MAX_WPATH, L"%s\\jre", app_dir);
    scan_vendor_root(sub_root, &best_ver, out_javaw, cap);
    if (best_ver >= 11) return true;

    /* 3. JAVA_HOME */
    wchar_t java_home[MAX_WPATH];
    DWORD jhLen = GetEnvironmentVariableW(L"JAVA_HOME", java_home, MAX_WPATH);
    if (jhLen > 0 && jhLen < MAX_WPATH) {
        wchar_t cand[MAX_WPATH];
        _snwprintf(cand, MAX_WPATH, L"%s\\bin\\javaw.exe", java_home);
        cand[MAX_WPATH - 1] = L'\0';
        check_candidate(cand, &best_ver, out_javaw, cap);
        if (best_ver >= 11) return true;
    }

    /* 4. System PATH */
    wchar_t path_hit[MAX_WPATH];
    DWORD spLen = SearchPathW(NULL, L"javaw.exe", NULL, MAX_WPATH, path_hit, NULL);
    if (spLen > 0 && spLen < MAX_WPATH) {
        check_candidate(path_hit, &best_ver, out_javaw, cap);
        if (best_ver >= 17) return true;
    }

    /* 5. Standard Windows 10/11 64-bit JDK/JRE vendor directories */
    wchar_t prog_files[MAX_WPATH];
    if (GetEnvironmentVariableW(L"ProgramW6432", prog_files, MAX_WPATH) == 0) {
        GetEnvironmentVariableW(L"ProgramFiles", prog_files, MAX_WPATH);
    }
    const wchar_t *vendors[] = {
        L"Eclipse Adoptium", L"Java", L"Microsoft", L"BellSoft",
        L"Amazon Corretto", L"Zulu", L"OpenJDK", L"Semeru", L"SapMachine", L"JetBrains"
    };
    for (size_t i = 0; i < sizeof(vendors) / sizeof(vendors[0]); i++) {
        wchar_t vdir[MAX_WPATH];
        _snwprintf(vdir, MAX_WPATH, L"%s\\%s", prog_files, vendors[i]);
        vdir[MAX_WPATH - 1] = L'\0';
        scan_vendor_root(vdir, &best_ver, out_javaw, cap);
    }

    wchar_t local_app[MAX_WPATH];
    if (GetEnvironmentVariableW(L"LOCALAPPDATA", local_app, MAX_WPATH) > 0) {
        wchar_t user_adopt[MAX_WPATH];
        _snwprintf(user_adopt, MAX_WPATH, L"%s\\Programs\\Eclipse Adoptium", local_app);
        scan_vendor_root(user_adopt, &best_ver, out_javaw, cap);

        /* 6. Bundled OpenJDK 17/21 runtimes present on Windows 10/11 gaming PCs */
        const wchar_t *mc_slots[] = { L"java-runtime-delta", L"java-runtime-gamma", L"java-runtime-beta" };
        for (size_t i = 0; i < 3; i++) {
            wchar_t mc_cand[MAX_WPATH];
            _snwprintf(mc_cand, MAX_WPATH,
                L"%s\\Packages\\Microsoft.4297127D64EC6_8wekyb3d8bbwe\\LocalCache\\Local\\runtime\\%s\\windows-x64\\%s\\bin\\javaw.exe",
                local_app, mc_slots[i], mc_slots[i]);
            mc_cand[MAX_WPATH - 1] = L'\0';
            check_candidate(mc_cand, &best_ver, out_javaw, cap);
        }
    }

    wchar_t appdata[MAX_WPATH];
    if (GetEnvironmentVariableW(L"APPDATA", appdata, MAX_WPATH) > 0) {
        const wchar_t *mc_slots[] = { L"java-runtime-delta", L"java-runtime-gamma", L"java-runtime-beta" };
        for (size_t i = 0; i < 3; i++) {
            wchar_t mc_cand[MAX_WPATH];
            _snwprintf(mc_cand, MAX_WPATH,
                L"%s\\.minecraft\\runtime\\%s\\windows-x64\\%s\\bin\\javaw.exe",
                appdata, mc_slots[i], mc_slots[i]);
            mc_cand[MAX_WPATH - 1] = L'\0';
            check_candidate(mc_cand, &best_ver, out_javaw, cap);
        }
    }

    return best_ver >= 11;
}

/*
 * Downloads portable Eclipse Temurin OpenJDK 21 JRE x64 (~45 MB) into %LOCALAPPDATA%\CS-2-JAVA\jre
 * without requiring Administrator privileges or modifying system PATH/Registry.
 */
static bool auto_download_portable_jre21(const wchar_t *app_dir) {
    ensure_dir_tree_w(app_dir);
    wchar_t jre_dir[MAX_WPATH];
    _snwprintf(jre_dir, MAX_WPATH, L"%s\\jre", app_dir);
    jre_dir[MAX_WPATH - 1] = L'\0';
    ensure_dir_tree_w(jre_dir);

    wchar_t ps_cmd[2048];
    _snwprintf(ps_cmd, 2048,
        L"powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -Command \""
        L"$ErrorActionPreference='Stop'; "
        L"$Host.UI.RawUI.WindowTitle='CS-2 JAVA 10.0 — Загрузка портативной среды OpenJDK 21 (~45 МБ)...'; "
        L"Write-Host '================================================================' -ForegroundColor Cyan; "
        L"Write-Host '  CS-2 JAVA 10.0 : Первичная докачка портативной Java 21 (x64)' -ForegroundColor Green; "
        L"Write-Host '================================================================' -ForegroundColor Cyan; "
        L"$dest='%s'; $zip=Join-Path $dest 'temurin21-jre.zip'; "
        L"$url='https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse?project=jdk'; "
        L"[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; "
        L"if (Get-Command curl.exe -ErrorAction SilentlyContinue) { "
        L"  & curl.exe -fL --retry 2 -o $zip $url "
        L"} else { "
        L"  Invoke-WebRequest -Uri $url -OutFile $zip "
        L"}; "
        L"Write-Host 'Распаковка портативного движка...' -ForegroundColor Yellow; "
        L"if (Get-Command tar.exe -ErrorAction SilentlyContinue) { "
        L"  & tar.exe -xf $zip -C $dest "
        L"} else { "
        L"  Expand-Archive -Path $zip -DestinationPath $dest -Force "
        L"}; "
        L"Remove-Item $zip -Force -ErrorAction SilentlyContinue; "
        L"Write-Host 'Готово! Запуск CS-2 JAVA 10.0...' -ForegroundColor Green\"",
        jre_dir);
    ps_cmd[2047] = L'\0';

    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    ZeroMemory(&pi, sizeof(pi));
    si.cb = sizeof(si);

    if (!CreateProcessW(NULL, ps_cmd, NULL, NULL, FALSE, 0, NULL, app_dir, &si, &pi)) {
        return false;
    }
    WaitForSingleObject(pi.hProcess, INFINITE);
    DWORD exit_code = 1;
    GetExitCodeProcess(pi.hProcess, &exit_code);
    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);
    return (exit_code == 0);
}

int WINAPI wWinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, PWSTR lpCmdLine, int nCmdShow) {
    (void)hInstance;
    (void)hPrevInstance;
    (void)nCmdShow;

    wchar_t exe_path[MAX_WPATH];
    DWORD mLen = GetModuleFileNameW(NULL, exe_path, MAX_WPATH);
    if (mLen == 0 || mLen >= MAX_WPATH) {
        MessageBoxW(NULL, L"Не удалось определить путь к CS-2-JAVA.exe.", L"CS-2 JAVA 10.0", MB_ICONERROR | MB_OK);
        return 1;
    }

    wchar_t exe_dir[MAX_WPATH];
    get_parent_dir(exe_path, exe_dir, MAX_WPATH);

    wchar_t local_app[MAX_WPATH];
    if (GetEnvironmentVariableW(L"LOCALAPPDATA", local_app, MAX_WPATH) == 0) {
        GetTempPathW(MAX_WPATH, local_app);
    }
    wchar_t app_dir[MAX_WPATH];
    _snwprintf(app_dir, MAX_WPATH, L"%s\\CS-2-JAVA", local_app);
    app_dir[MAX_WPATH - 1] = L'\0';

    wchar_t jar_path[MAX_WPATH];
    if (!extract_jar_if_needed(exe_dir, app_dir, jar_path, MAX_WPATH)) {
        MessageBoxW(NULL, L"Ошибка подготовки игрового архива CS-2-JAVA-10.0.jar.\nПроверьте свободное место на диске.",
                    L"CS-2 JAVA 10.0", MB_ICONERROR | MB_OK);
        return 1;
    }

    wchar_t javaw_path[MAX_WPATH];
    if (!find_java11_runtime(exe_dir, app_dir, javaw_path, MAX_WPATH)) {
        int choice = MessageBoxW(
            NULL,
            L"На этом компьютере пока не найдена среда Java 11+ (64-bit).\n\n"
            L"Скачать компактную портативную среду OpenJDK 21 (~45 МБ) автоматически "
            L"(без прав администратора и без установки в систему) и сразу запустить CS-2 JAVA 10.0?\n\n"
            L"• «Да» — скачать портативную Java 21 автоматически и запустить игру\n"
            L"• «Нет» — открыть официальную страницу загрузки Temurin JDK 21 в браузере",
            L"CS-2 JAVA 10.0 — Первый запуск на Windows 10/11",
            MB_YESNOCANCEL | MB_ICONQUESTION
        );

        if (choice == IDYES) {
            if (!auto_download_portable_jre21(app_dir)
                || !find_java11_runtime(exe_dir, app_dir, javaw_path, MAX_WPATH)) {
                MessageBoxW(
                    NULL,
                    L"Не удалось автоматически скачать портативную Java 21 (проверьте подключение к интернету).\n\n"
                    L"Сейчас откроется страница загрузки Eclipse Adoptium Temurin 21.",
                    L"CS-2 JAVA 10.0",
                    MB_ICONWARNING | MB_OK
                );
                ShellExecuteW(NULL, L"open", L"https://adoptium.net/temurin/releases/?version=21&os=windows&arch=x64", NULL, NULL, SW_SHOWNORMAL);
                return 1;
            }
        } else if (choice == IDNO) {
            ShellExecuteW(NULL, L"open", L"https://adoptium.net/temurin/releases/?version=21&os=windows&arch=x64", NULL, NULL, SW_SHOWNORMAL);
            return 0;
        } else {
            return 0;
        }
    }

    const wchar_t *work_dir = is_dir_writable_w(exe_dir) ? exe_dir : app_dir;

    wchar_t launch_cmd[3072];
    if (lpCmdLine && lpCmdLine[0] != L'\0') {
        _snwprintf(launch_cmd, 3072,
            L"\"%s\" -Xms256m -Xmx1536m -XX:+UseG1GC -XX:MaxGCPauseMillis=8 "
            L"-Dsun.java2d.d3d=false -Dsun.java2d.noddraw=true -Dsun.java2d.uiScale=1.0 "
            L"-Dfile.encoding=UTF-8 -jar \"%s\" %s",
            javaw_path, jar_path, lpCmdLine);
    } else {
        _snwprintf(launch_cmd, 3072,
            L"\"%s\" -Xms256m -Xmx1536m -XX:+UseG1GC -XX:MaxGCPauseMillis=8 "
            L"-Dsun.java2d.d3d=false -Dsun.java2d.noddraw=true -Dsun.java2d.uiScale=1.0 "
            L"-Dfile.encoding=UTF-8 -jar \"%s\"",
            javaw_path, jar_path);
    }
    launch_cmd[3071] = L'\0';

    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    ZeroMemory(&pi, sizeof(pi));
    si.cb = sizeof(si);

    if (!CreateProcessW(javaw_path, launch_cmd, NULL, NULL, FALSE, 0, NULL, work_dir, &si, &pi)) {
        wchar_t err_msg[MAX_WPATH + 256];
        _snwprintf(err_msg, MAX_WPATH + 256,
            L"Не удалось запустить процесс javaw.exe (код %lu):\n%s",
            (unsigned long)GetLastError(), javaw_path);
        err_msg[MAX_WPATH + 255] = L'\0';
        MessageBoxW(NULL, err_msg, L"CS-2 JAVA 10.0 — Ошибка запуска", MB_ICONERROR | MB_OK);
        return 1;
    }

    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);
    return 0;
}
