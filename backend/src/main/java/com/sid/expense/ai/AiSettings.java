package com.sid.expense.ai;

import com.sid.expense.config.AppProperties;
import com.sid.expense.repo.SettingsRepository;
import org.springframework.stereotype.Component;

/** The Gemini key and model in use: saved in Settings by an admin, else GEMINI_API_KEY / GEMINI_MODEL. */
@Component
public class AiSettings {

  static final String API_KEY = "gemini.api_key";
  static final String MODEL = "gemini.model";

  public enum Source { SETTINGS, ENVIRONMENT, NONE }

  public record Current(String apiKey, String model, Source source) {
    public boolean enabled() {
      return source != Source.NONE;
    }
  }

  private final SettingsRepository settings;
  private final AppProperties props;
  private volatile Current current;

  public AiSettings(SettingsRepository settings, AppProperties props) {
    this.settings = settings;
    this.props = props;
  }

  public Current current() {
    Current c = current;
    return c != null ? c : reload();
  }

  public void save(String apiKey, String model) {
    settings.put(API_KEY, apiKey);
    settings.put(MODEL, model);
    reload();
  }

  /** Forgets the saved key so the environment's key (if any) is used again. */
  public void clear() {
    settings.delete(API_KEY);
    settings.delete(MODEL);
    reload();
  }

  public String defaultModel() {
    return props.gemini().model();
  }

  private synchronized Current reload() {
    String model = settings.get(MODEL).orElse(props.gemini().model());
    String saved = settings.get(API_KEY).orElse(null);
    String env = props.gemini().apiKey();
    current = saved != null ? new Current(saved, model, Source.SETTINGS)
        : env != null && !env.isBlank() ? new Current(env.trim(), model, Source.ENVIRONMENT)
        : new Current(null, model, Source.NONE);
    return current;
  }
}
