package com.hackalem.domain.port;
import static com.hackalem.domain.port.Contracts.*;
public interface CartPort {
    CartSnapshot get(TrustedScope scope);
    /** Atomic all-or-nothing, durable dedup by proposal.operationId; validates existing + added stock. */
    OperationOutcome addConditionally(TrustedScope scope, ProposalSnapshot proposal);
    /** Empty means not known, NOT permission to retry an uncertain mutation. */
    java.util.Optional<OperationOutcome> lookupOperation(TrustedScope scope, String operationId);
    /** Default remote recovery is lookup-only. A local atomic adapter may safely resume its durable intent. */
    default java.util.Optional<OperationOutcome> reconcile(TrustedScope scope, ProposalSnapshot proposal) {
        return lookupOperation(scope, proposal.operationId());
    }
}
