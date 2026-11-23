#pragma once
#include <atomic>
#include <cstdint>
#include <string>

#include "PaymentHubService.h"

namespace payhub::network {

// Channel-facing TCP server (Winsock) used by internet banking, branch and
// corporate channels. Line protocol, one command per line:
//   SUBMIT <endToEndId> <debtorAcct> <creditorAcct> <creditorAgent> <amountMinor> <currency> <NORMAL|URGENT>
//   SETTLED <endToEndId> <railReference>      (callback from rail-connector-service)
//   STATUS <endToEndId>
class TcpHubServer {
public:
    TcpHubServer(core::PaymentHubService& service, std::uint16_t port);

    void Run();    // blocking accept loop; one std::thread per connection
    void Stop();

private:
    void HandleClient(std::uintptr_t clientSocket);
    std::string HandleCommand(const std::string& line);

    core::PaymentHubService& service_;
    std::uint16_t port_;
    std::atomic<bool> running_{false};
};

} // namespace payhub::network
