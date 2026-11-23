#pragma once
#include <string>

#include "ISanctionsScreener.h"

namespace payhub::integration {

// Runs an external Python sanctions/AML screening script as a child process:
//   python <scriptPath> <debtorAccount> <creditorAccount> <creditorAgent> <amountMinor> <currency>
// The script's exit code is the match score, 0-100.
class PythonSanctionsScreener : public core::ISanctionsScreener {
public:
    explicit PythonSanctionsScreener(std::string scriptPath, std::string pythonExe = "python");

    int Screen(const core::Payment& payment) override;

private:
    int RunScript(const std::string& arguments);

    std::string scriptPath_;
    std::string pythonExe_;
};

} // namespace payhub::integration
