package io.github.chdurai.agenticbanking.tools.port;

public interface TicketPort extends ToolPort {

    record TicketRequest(String title, String details, String severity) {
    }

    record TicketResult(String ticketId, String status) {
    }

    TicketResult create(TicketRequest request);

    @Override
    default String id() {
        return "ticket.create";
    }

    @Override
    default SideEffect sideEffect() {
        return SideEffect.IRREVERSIBLE;
    }
}
