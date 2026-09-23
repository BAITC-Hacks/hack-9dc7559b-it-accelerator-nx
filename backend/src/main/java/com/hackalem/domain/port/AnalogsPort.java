package com.hackalem.domain.port;
import static com.hackalem.domain.port.Contracts.*;
import java.util.List;
public interface AnalogsPort { List<AlternativePlan> find(String article, SearchQuery constraints, TrustedScope scope); }
