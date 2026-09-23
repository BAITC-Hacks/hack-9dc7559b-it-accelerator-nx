package com.hackalem.domain.port;
import static com.hackalem.domain.port.Contracts.*;
/** Implement only after verification of partner signature, issuer, audience and server cart binding. */
public interface PartnerIdentityPort { TrustedScope exchange(String signedAssertion); }
