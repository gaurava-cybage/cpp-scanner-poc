#include "HttpRailConnector.h"

#include <utility>

#include <winsock2.h>
#include <ws2tcpip.h>

#include "Logger.h"

namespace payhub::network {

namespace {
constexpr const char* kRailsEndpoint = "/api/v1/rails/";
constexpr const char* kAccepted      = "ACCP";
}

HttpRailConnector::HttpRailConnector(std::string host, std::string port, const Pacs008Builder& messageBuilder)
    : host_(std::move(host)), port_(std::move(port)), messageBuilder_(messageBuilder) {}

core::RailSubmission HttpRailConnector::Submit(const core::Payment& payment) {
    core::RailSubmission submission;
    std::string path = std::string(kRailsEndpoint) + core::ToString(payment.GetRail()) + "/submit";

    auto body = HttpPost(path, messageBuilder_.Build(payment));
    if (!body) {
        submission.reasonCode = "NARR";   // connector unreachable
        return submission;
    }

    auto separator = body->find('|');
    std::string status = body->substr(0, separator);
    std::string detail = separator == std::string::npos ? "" : body->substr(separator + 1);

    submission.accepted = (status == kAccepted);
    if (submission.accepted) {
        submission.railReference = detail;
    } else {
        submission.reasonCode = detail;
    }
    return submission;
}

std::optional<std::string> HttpRailConnector::HttpPost(const std::string& path, const std::string& xmlBody) {
    addrinfo hints{};
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;

    addrinfo* result = nullptr;
    if (getaddrinfo(host_.c_str(), port_.c_str(), &hints, &result) != 0) {
        PHUB_LOG_ERROR("Cannot resolve rail-connector-service host " + host_);
        return std::nullopt;
    }

    SOCKET sock = socket(result->ai_family, result->ai_socktype, result->ai_protocol);
    if (sock == INVALID_SOCKET ||
        connect(sock, result->ai_addr, static_cast<int>(result->ai_addrlen)) == SOCKET_ERROR) {
        freeaddrinfo(result);
        if (sock != INVALID_SOCKET) closesocket(sock);
        PHUB_LOG_ERROR("Cannot connect to rail-connector-service at " + host_ + ":" + port_);
        return std::nullopt;
    }
    freeaddrinfo(result);

    std::string request = "POST " + path + " HTTP/1.1\r\n"
                          "Host: " + host_ + "\r\n"
                          "Content-Type: application/xml\r\n"
                          "Content-Length: " + std::to_string(xmlBody.size()) + "\r\n"
                          "Connection: close\r\n\r\n" + xmlBody;
    send(sock, request.c_str(), static_cast<int>(request.size()), 0);

    std::string response;
    char buffer[1024];
    int received = 0;
    while ((received = recv(sock, buffer, sizeof(buffer), 0)) > 0) {
        response.append(buffer, received);
    }
    closesocket(sock);

    auto bodyStart = response.find("\r\n\r\n");
    if (bodyStart == std::string::npos) {
        return std::nullopt;
    }
    return response.substr(bodyStart + 4);
}

} // namespace payhub::network
