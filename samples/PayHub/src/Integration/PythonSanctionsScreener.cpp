#include "PythonSanctionsScreener.h"

#include <utility>

#ifdef _WIN32
#include <windows.h>
#else
#include <cstdio>
#include <sys/wait.h>
#endif

#include "Logger.h"

namespace payhub::integration {

namespace {
// Fail closed: if screening cannot run, hold the payment as a potential hit.
constexpr int kUnavailableScore = 100;
}

PythonSanctionsScreener::PythonSanctionsScreener(std::string scriptPath, std::string pythonExe)
    : scriptPath_(std::move(scriptPath)), pythonExe_(std::move(pythonExe)) {}

int PythonSanctionsScreener::Screen(const core::Payment& payment) {
    int score = RunScript(payment.DebtorAccount() + " " + payment.CreditorAccount() + " " +
                          payment.CreditorAgent() + " " + std::to_string(payment.AmountMinor()) + " " +
                          payment.Currency());
    if (score < 0 || score > 100) {
        PHUB_LOG_ERROR("Sanctions screening unavailable for " + payment.EndToEndId() + "; failing closed");
        return kUnavailableScore;
    }
    return score;
}

int PythonSanctionsScreener::RunScript(const std::string& arguments) {
    std::string commandLine = pythonExe_ + " \"" + scriptPath_ + "\" " + arguments;

#ifdef _WIN32
    STARTUPINFOA startup{};
    startup.cb = sizeof(startup);
    PROCESS_INFORMATION process{};

    if (!CreateProcessA(nullptr, commandLine.data(), nullptr, nullptr, FALSE,
                        CREATE_NO_WINDOW, nullptr, nullptr, &startup, &process)) {
        PHUB_LOG_ERROR("CreateProcess failed for: " + commandLine);
        return -1;
    }
    WaitForSingleObject(process.hProcess, 5000);

    DWORD exitCode = 0;
    GetExitCodeProcess(process.hProcess, &exitCode);
    CloseHandle(process.hThread);
    CloseHandle(process.hProcess);
    return static_cast<int>(exitCode);
#else
    FILE* pipe = popen(commandLine.c_str(), "r");
    if (pipe == nullptr) {
        return -1;
    }
    int status = pclose(pipe);
    return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
#endif
}

} // namespace payhub::integration
