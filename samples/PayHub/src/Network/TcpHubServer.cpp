#include "TcpHubServer.h"

#include <sstream>
#include <thread>

#include <winsock2.h>

#include "Logger.h"

namespace payhub::network {

TcpHubServer::TcpHubServer(core::PaymentHubService& service, std::uint16_t port)
    : service_(service), port_(port) {}

void TcpHubServer::Run() {
    SOCKET listener = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (listener == INVALID_SOCKET) {
        PHUB_LOG_ERROR("socket() failed");
        return;
    }

    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_ANY);
    address.sin_port = htons(port_);

    if (bind(listener, reinterpret_cast<sockaddr*>(&address), sizeof(address)) == SOCKET_ERROR ||
        listen(listener, SOMAXCONN) == SOCKET_ERROR) {
        PHUB_LOG_ERROR("bind/listen failed on port " + std::to_string(port_));
        closesocket(listener);
        return;
    }

    running_ = true;
    PHUB_LOG_INFO("Payment hub listening on port " + std::to_string(port_));

    while (running_) {
        SOCKET client = accept(listener, nullptr, nullptr);
        if (client == INVALID_SOCKET) {
            continue;
        }
        std::thread(&TcpHubServer::HandleClient, this, static_cast<std::uintptr_t>(client)).detach();
    }
    closesocket(listener);
}

void TcpHubServer::Stop() {
    running_ = false;
}

void TcpHubServer::HandleClient(std::uintptr_t clientSocket) {
    SOCKET client = static_cast<SOCKET>(clientSocket);
    std::string pending;
    char buffer[512];
    int received = 0;

    while ((received = recv(client, buffer, sizeof(buffer), 0)) > 0) {
        pending.append(buffer, received);
        std::size_t newline;
        while ((newline = pending.find('\n')) != std::string::npos) {
            std::string reply = HandleCommand(pending.substr(0, newline)) + "\n";
            send(client, reply.c_str(), static_cast<int>(reply.size()), 0);
            pending.erase(0, newline + 1);
        }
    }
    closesocket(client);
}

std::string TcpHubServer::HandleCommand(const std::string& line) {
    std::istringstream in(line);
    std::string command;
    in >> command;

    if (command == "SUBMIT") {
        std::string endToEndId, debtor, creditor, agent, currency, priority;
        std::int64_t amountMinor = 0;
        in >> endToEndId >> debtor >> creditor >> agent >> amountMinor >> currency >> priority;
        core::Payment payment(endToEndId, debtor, creditor, agent, amountMinor, currency,
                              priority == "URGENT" ? core::Priority::Urgent : core::Priority::Normal);
        return service_.Process(std::move(payment)).ToJson();
    }

    if (command == "SETTLED") {
        std::string endToEndId, railReference;
        in >> endToEndId >> railReference;
        auto payment = service_.ConfirmSettlement(endToEndId, railReference);
        return payment ? payment->ToJson() : "{\"error\":\"settlement_ignored\"}";
    }

    if (command == "STATUS") {
        std::string endToEndId;
        in >> endToEndId;
        auto payment = service_.GetStatus(endToEndId);
        return payment ? payment->ToJson() : "{\"error\":\"not_found\"}";
    }

    return "{\"error\":\"unknown_command\"}";
}

} // namespace payhub::network
