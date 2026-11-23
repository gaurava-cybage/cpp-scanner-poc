#pragma once
#include <optional>
#include <string>

#include "IRailConnector.h"
#include "Pacs008Builder.h"

namespace payhub::network {

// Calls the external Java (Spring Boot) "rail-connector-service", which holds the
// actual connections to IMPS / NEFT / RTGS / SWIFT.
//   POST http://<host>:<port>/api/v1/rails/{rail}/submit   body: pacs.008 XML
// Response body: "ACCP|<railReference>" or "RJCT|<reasonCode>"
class HttpRailConnector : public core::IRailConnector {
public:
    HttpRailConnector(std::string host, std::string port, const Pacs008Builder& messageBuilder);

    core::RailSubmission Submit(const core::Payment& payment) override;

private:
    std::optional<std::string> HttpPost(const std::string& path, const std::string& xmlBody);

    std::string host_;
    std::string port_;
    const Pacs008Builder& messageBuilder_;
};

} // namespace payhub::network
