// Windows x64 Discord Rich Presence DLL for Range Display.
// Exports: DiscordRPC_Start, DiscordRPC_Update, DiscordRPC_Stop,
//          DiscordRPC_GetStatus, DiscordRPC_GetLastError.
// Build: x86_64-w64-mingw32-gcc -O2 -shared -o DiscordRPC.dll DiscordRPC.c
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

#define FIELD_CAP 512
#define FRAME_CAP 8192
#define STATUS_STOPPED 0
#define STATUS_CONNECTING 1
#define STATUS_CONNECTED 2
#define STATUS_ERROR 3

static CRITICAL_SECTION g_lock;
static HANDLE g_thread = NULL;
static volatile LONG g_running = 0;
static volatile LONG g_status = STATUS_STOPPED;
static char g_status_message[256] = "Stopped";
static char g_client_id[64];
static char g_details[FIELD_CAP] = "Range Display";
static char g_state[FIELD_CAP] = "Playing Minecraft";
static unsigned long g_revision = 0;
static unsigned long g_sent_revision = 0;
static long long g_start_ts = 0;
static DWORD g_pid = 0;

static void set_status(LONG status, const char *message) {
    InterlockedExchange(&g_status, status);
    if (g_running) {
        EnterCriticalSection(&g_lock);
        strncpy_s(g_status_message, sizeof(g_status_message), message ? message : "", _TRUNCATE);
        LeaveCriticalSection(&g_lock);
    } else {
        strncpy_s(g_status_message, sizeof(g_status_message), message ? message : "", _TRUNCATE);
    }
}

static int write_all(HANDLE pipe, const void *data, DWORD length) {
    const char *cursor = (const char *)data;
    while (length > 0) {
        DWORD written = 0;
        if (!WriteFile(pipe, cursor, length, &written, NULL) || written == 0) return 0;
        cursor += written;
        length -= written;
    }
    return 1;
}

static int read_all(HANDLE pipe, void *data, DWORD length) {
    char *cursor = (char *)data;
    while (length > 0) {
        DWORD received = 0;
        if (!ReadFile(pipe, cursor, length, &received, NULL) || received == 0) return 0;
        cursor += received;
        length -= received;
    }
    return 1;
}

static int send_frame(HANDLE pipe, uint32_t opcode, const char *json) {
    uint32_t length = (uint32_t)strlen(json);
    uint32_t header[2] = { opcode, length }; // Discord IPC: two little-endian uint32 values.
    return write_all(pipe, header, (DWORD)sizeof(header)) && write_all(pipe, json, length);
}

static int read_frame(HANDLE pipe, char *payload, size_t capacity, uint32_t *opcode) {
    uint32_t header[2];
    if (!read_all(pipe, header, (DWORD)sizeof(header))) return 0;
    if (header[1] >= capacity) return 0;
    if (!read_all(pipe, payload, header[1])) return 0;
    payload[header[1]] = '\0';
    *opcode = header[0];
    return 1;
}

static void escape_json(const char *input, char *output, size_t capacity) {
    size_t out = 0;
    for (size_t i = 0; input[i] && out + 7 < capacity; ++i) {
        unsigned char c = (unsigned char)input[i];
        if (c == '"' || c == '\\') { output[out++] = '\\'; output[out++] = (char)c; }
        else if (c == '\n') { output[out++] = '\\'; output[out++] = 'n'; }
        else if (c == '\r') { output[out++] = '\\'; output[out++] = 'r'; }
        else if (c == '\t') { output[out++] = '\\'; output[out++] = 't'; }
        else if (c >= 0x20) output[out++] = (char)c;
    }
    output[out] = '\0';
}

static HANDLE connect_discord(void) {
    for (int i = 0; i < 10 && g_running; ++i) {
        char name[64];
        // Discord's documented Windows IPC path uses the extended namespace.
        snprintf(name, sizeof(name), "\\\\?\\pipe\\discord-ipc-%d", i);
        HANDLE pipe = CreateFileA(name, GENERIC_READ | GENERIC_WRITE, 0, NULL,
                                  OPEN_EXISTING, 0, NULL);
        if (pipe != INVALID_HANDLE_VALUE) return pipe;
        DWORD error = GetLastError();
        if (error == ERROR_PIPE_BUSY) {
            if (WaitNamedPipeA(name, 500) && g_running) {
                pipe = CreateFileA(name, GENERIC_READ | GENERIC_WRITE, 0, NULL,
                                   OPEN_EXISTING, 0, NULL);
                if (pipe != INVALID_HANDLE_VALUE) return pipe;
            }
        }
    }
    return INVALID_HANDLE_VALUE;
}

static int perform_handshake(HANDLE pipe) {
    char request[192];
    snprintf(request, sizeof(request), "{\"v\":1,\"client_id\":\"%s\"}", g_client_id);
    if (!send_frame(pipe, 0, request)) {
        set_status(STATUS_ERROR, "Failed to send Discord IPC handshake");
        return 0;
    }
    DWORD deadline = GetTickCount() + 7000;
    while (g_running && (LONG)(deadline - GetTickCount()) > 0) {
        DWORD available = 0;
        if (!PeekNamedPipe(pipe, NULL, 0, NULL, &available, NULL)) {
            set_status(STATUS_ERROR, "Discord IPC closed during handshake");
            return 0;
        }
        if (available >= 8) {
            char response[FRAME_CAP];
            uint32_t opcode = 0;
            if (!read_frame(pipe, response, sizeof(response), &opcode)) {
                set_status(STATUS_ERROR, "Could not read Discord IPC handshake response");
                return 0;
            }
            if (opcode == 1 && strstr(response, "READY")) return 1;
            char message[256];
            snprintf(message, sizeof(message), "Discord IPC handshake rejected: %.200s", response);
            set_status(STATUS_ERROR, message);
            return 0;
        }
        Sleep(50);
    }
    set_status(STATUS_ERROR, "Timed out waiting for Discord IPC READY");
    return 0;
}

static int send_activity(HANDLE pipe, const char *details, const char *state) {
    char escaped_details[FIELD_CAP * 2];
    char escaped_state[FIELD_CAP * 2];
    char json[FRAME_CAP];
    escape_json(details, escaped_details, sizeof(escaped_details));
    escape_json(state, escaped_state, sizeof(escaped_state));
    snprintf(json, sizeof(json),
        "{\"cmd\":\"SET_ACTIVITY\",\"args\":{\"pid\":%lu,\"activity\":{" 
        "\"details\":\"%s\",\"state\":\"%s\",\"timestamps\":{\"start\":%lld},"
        "\"buttons\":[{\"label\":\"Download\",\"url\":\"https://modrinth.com/organization/space\"},"
        "{\"label\":\"GitHub\",\"url\":\"https://github.com/SpaceUtils\"}]}} ,"
        "\"nonce\":\"%lu\"}",
        (unsigned long)g_pid, escaped_details, escaped_state, g_start_ts,
        (unsigned long)GetTickCount());
    return send_frame(pipe, 1, json);
}

static int reply_to_ping(HANDLE pipe, const char *payload, size_t length) {
    char copy[FRAME_CAP];
    if (length >= sizeof(copy)) return 0;
    memcpy(copy, payload, length);
    copy[length] = '\0';
    return send_frame(pipe, 4, copy);
}

static DWORD WINAPI rpc_loop(LPVOID unused) {
    (void)unused;
    HANDLE pipe = INVALID_HANDLE_VALUE;
    while (g_running) {
        if (pipe == INVALID_HANDLE_VALUE) {
            set_status(STATUS_CONNECTING, "Searching Discord IPC pipes \\\\?\\pipe\\discord-ipc-0..9");
            pipe = connect_discord();
            if (pipe == INVALID_HANDLE_VALUE) {
                set_status(STATUS_CONNECTING, "No Discord IPC pipe available; retrying");
                Sleep(2000);
                continue;
            }
            set_status(STATUS_CONNECTING, "Discord IPC pipe opened; sending handshake");
            if (!perform_handshake(pipe)) {
                CloseHandle(pipe);
                pipe = INVALID_HANDLE_VALUE;
                Sleep(1500);
                continue;
            }
            set_status(STATUS_CONNECTED, "Connected to Discord IPC (READY)");
            g_sent_revision = 0;
        }

        DWORD available = 0;
        if (!PeekNamedPipe(pipe, NULL, 0, NULL, &available, NULL)) {
            CloseHandle(pipe);
            pipe = INVALID_HANDLE_VALUE;
            set_status(STATUS_CONNECTING, "Discord IPC disconnected; reconnecting");
            continue;
        }
        if (available >= 8) {
            char response[FRAME_CAP];
            uint32_t opcode = 0;
            if (!read_frame(pipe, response, sizeof(response), &opcode)) {
                CloseHandle(pipe);
                pipe = INVALID_HANDLE_VALUE;
                set_status(STATUS_CONNECTING, "Invalid Discord IPC frame; reconnecting");
                continue;
            }
            if (opcode == 3 && !reply_to_ping(pipe, response, strlen(response))) {
                CloseHandle(pipe);
                pipe = INVALID_HANDLE_VALUE;
                set_status(STATUS_CONNECTING, "Failed to reply to Discord IPC ping");
                continue;
            }
            if (opcode == 2) {
                CloseHandle(pipe);
                pipe = INVALID_HANDLE_VALUE;
                set_status(STATUS_CONNECTING, "Discord IPC requested close; reconnecting");
                continue;
            }
        }

        char details[FIELD_CAP];
        char state[FIELD_CAP];
        unsigned long revision;
        EnterCriticalSection(&g_lock);
        memcpy(details, g_details, sizeof(details));
        memcpy(state, g_state, sizeof(state));
        revision = g_revision;
        LeaveCriticalSection(&g_lock);
        if (revision != g_sent_revision && !send_activity(pipe, details, state)) {
            CloseHandle(pipe);
            pipe = INVALID_HANDLE_VALUE;
            set_status(STATUS_CONNECTING, "Failed to send SET_ACTIVITY; reconnecting");
            continue;
        }
        g_sent_revision = revision;
        Sleep(250);
    }

    if (pipe != INVALID_HANDLE_VALUE) {
        char clear[256];
        snprintf(clear, sizeof(clear),
            "{\"cmd\":\"SET_ACTIVITY\",\"args\":{\"pid\":%lu,\"activity\":null},\"nonce\":\"%lu\"}",
            (unsigned long)g_pid, (unsigned long)GetTickCount());
        send_frame(pipe, 1, clear);
        CloseHandle(pipe);
    }
    set_status(STATUS_STOPPED, "Stopped");
    return 0;
}

__declspec(dllexport) int DiscordRPC_Start(const char *client_id) {
    if (!client_id || !client_id[0] || strlen(client_id) >= sizeof(g_client_id)) return 0;
    if (InterlockedCompareExchange(&g_running, 1, 0) != 0) return 1;
    InitializeCriticalSection(&g_lock);
    strcpy_s(g_client_id, sizeof(g_client_id), client_id);
    g_pid = GetCurrentProcessId();
    g_start_ts = (long long)time(NULL);
    g_revision = 1;
    g_sent_revision = 0;
    strncpy_s(g_status_message, sizeof(g_status_message), "Starting Discord IPC worker", _TRUNCATE);
    InterlockedExchange(&g_status, STATUS_CONNECTING);
    g_thread = CreateThread(NULL, 0, rpc_loop, NULL, 0, NULL);
    if (!g_thread) {
        InterlockedExchange(&g_running, 0);
        InterlockedExchange(&g_status, STATUS_ERROR);
        strncpy_s(g_status_message, sizeof(g_status_message), "CreateThread failed", _TRUNCATE);
        DeleteCriticalSection(&g_lock);
        return 0;
    }
    return 1;
}

__declspec(dllexport) void DiscordRPC_Update(const char *details, const char *state) {
    if (!g_running) return;
    EnterCriticalSection(&g_lock);
    strncpy_s(g_details, sizeof(g_details), details ? details : "Range Display", _TRUNCATE);
    strncpy_s(g_state, sizeof(g_state), state ? state : "Playing Minecraft", _TRUNCATE);
    ++g_revision;
    LeaveCriticalSection(&g_lock);
}

__declspec(dllexport) int DiscordRPC_GetStatus(void) {
    return (int)InterlockedCompareExchange(&g_status, 0, 0);
}

__declspec(dllexport) const char *DiscordRPC_GetLastError(void) {
    return g_status_message;
}

__declspec(dllexport) void DiscordRPC_Stop(void) {
    if (InterlockedExchange(&g_running, 0) == 0) return;
    if (g_thread) {
        WaitForSingleObject(g_thread, 4000);
        CloseHandle(g_thread);
        g_thread = NULL;
    }
    DeleteCriticalSection(&g_lock);
    InterlockedExchange(&g_status, STATUS_STOPPED);
}

static int module_path_is_expected(HINSTANCE instance) {
    wchar_t path[32768];
    static const wchar_t suffix[] = L"\\SpaceUtils\\native\\win\\DiscordRPC.dll";
    DWORD length = GetModuleFileNameW(instance, path, (DWORD)(sizeof(path) / sizeof(path[0])));
    if (length == 0 || length >= sizeof(path) / sizeof(path[0])) return 0;
    for (DWORD i = 0; i < length; ++i) if (path[i] == L'/') path[i] = L'\\';
    size_t suffix_length = wcslen(suffix);
    if ((size_t)length < suffix_length) return 0;
    const wchar_t *tail = path + length - suffix_length;
    for (size_t i = 0; i < suffix_length; ++i) {
        wchar_t actual = tail[i];
        wchar_t expected = suffix[i];
        if (actual >= L'A' && actual <= L'Z') actual += L'a' - L'A';
        if (expected >= L'A' && expected <= L'Z') expected += L'a' - L'A';
        if (actual != expected) return 0;
    }
    return 1;
}

BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID reserved) {
    (void)reserved;
    if (reason == DLL_PROCESS_ATTACH) {
        if (!module_path_is_expected(instance)) return FALSE;
        DisableThreadLibraryCalls(instance);
    }
    return TRUE;
}
