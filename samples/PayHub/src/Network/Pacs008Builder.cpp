#include "Pacs008Builder.h"

#include <cstdio>
#include <sstream>
#include <utility>

namespace payhub::network {

namespace {
constexpr const char* kPacs008Namespace = "urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08";
}

Pacs008Builder::Pacs008Builder(std::string instructingAgentBic)
    : instructingAgentBic_(std::move(instructingAgentBic)) {}

std::string Pacs008Builder::Build(const core::Payment& payment) const {
    std::ostringstream xml;
    xml << "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        << "<Document xmlns=\"" << kPacs008Namespace << "\"><FIToFICstmrCdtTrf>"
        << "<GrpHdr><MsgId>" << Escape(payment.EndToEndId()) << "</MsgId><NbOfTxs>1</NbOfTxs>"
        << "<SttlmInf><SttlmMtd>CLRG</SttlmMtd><ClrSys><Prtry>" << core::ToString(payment.GetRail())
        << "</Prtry></ClrSys></SttlmInf></GrpHdr>"
        << "<CdtTrfTxInf>"
        << "<PmtId><EndToEndId>" << Escape(payment.EndToEndId()) << "</EndToEndId></PmtId>"
        << "<PmtTpInf><InstrPrty>" << (payment.GetPriority() == core::Priority::Urgent ? "HIGH" : "NORM")
        << "</InstrPrty></PmtTpInf>"
        << "<IntrBkSttlmAmt Ccy=\"" << Escape(payment.Currency()) << "\">"
        << FormatAmount(payment.AmountMinor()) << "</IntrBkSttlmAmt>"
        << "<InstgAgt><FinInstnId><BICFI>" << Escape(instructingAgentBic_) << "</BICFI></FinInstnId></InstgAgt>"
        << "<DbtrAcct><Id><Othr><Id>" << Escape(payment.DebtorAccount()) << "</Id></Othr></Id></DbtrAcct>"
        << "<CdtrAgt><FinInstnId><Othr><Id>" << Escape(payment.CreditorAgent()) << "</Id></Othr></FinInstnId></CdtrAgt>"
        << "<CdtrAcct><Id><Othr><Id>" << Escape(payment.CreditorAccount()) << "</Id></Othr></Id></CdtrAcct>"
        << "</CdtTrfTxInf></FIToFICstmrCdtTrf></Document>";
    return xml.str();
}

std::string Pacs008Builder::FormatAmount(std::int64_t amountMinor) {
    char buffer[32];
    std::snprintf(buffer, sizeof(buffer), "%lld.%02lld",
                  static_cast<long long>(amountMinor / 100), static_cast<long long>(amountMinor % 100));
    return buffer;
}

std::string Pacs008Builder::Escape(const std::string& value) {
    std::string out;
    out.reserve(value.size());
    for (char c : value) {
        switch (c) {
        case '&': out += "&amp;"; break;
        case '<': out += "&lt;"; break;
        case '>': out += "&gt;"; break;
        case '"': out += "&quot;"; break;
        default:  out += c;
        }
    }
    return out;
}

} // namespace payhub::network
