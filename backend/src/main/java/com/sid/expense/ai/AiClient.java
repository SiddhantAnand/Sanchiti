package com.sid.expense.ai;

import java.util.Map;

public interface AiClient {

  boolean isEnabled();

  /** Calls the model with a JSON response schema; returns parsed JSON (Map/List/String/Number). */
  AiResult generate(String system, String prompt, Map<String, Object> schema);

  record AiResult(Object data, String model, Object usage) {}
}
