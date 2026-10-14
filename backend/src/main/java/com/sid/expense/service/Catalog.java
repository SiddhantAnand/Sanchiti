package com.sid.expense.service;

import com.sid.expense.config.AppProperties;
import com.sid.expense.domain.Banks;
import com.sid.expense.domain.Category;
import com.sid.expense.domain.Direction;
import com.sid.expense.repo.UserListsRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/** The categories and banks a user can pick: the built-in ones plus their own. */
@Service
public class Catalog {

  public static final int MAX_NAME = 40;

  private final UserListsRepository lists;
  private final List<String> knownBanks;

  public Catalog(UserListsRepository lists, AppProperties props) {
    this.lists = lists;
    this.knownBanks = props.knownBanks() == null ? List.of() : props.knownBanks();
  }

  public List<String> categories(long userId, Direction direction) {
    return Stream.concat(
            Category.labels(direction).stream(),
            lists.categories(userId).stream().filter(c -> c.direction() == direction).map(c -> c.name()))
        .toList();
  }

  /** The stored spelling of {@code label} if it is a category for this direction (case-insensitive). */
  public Optional<String> category(long userId, String label, Direction direction) {
    if (label == null) return Optional.empty();
    return categories(userId, direction).stream().filter(c -> c.equalsIgnoreCase(label.trim())).findFirst();
  }

  public boolean isCategory(long userId, String label) {
    return category(userId, label, Direction.DEBIT).isPresent() || category(userId, label, Direction.CREDIT).isPresent();
  }

  /** Adds a category (or returns the existing one with that name), rejecting clashes with built-in names. */
  public String addCategory(long userId, String rawName, Direction direction) {
    String name = cleanName(rawName);
    Optional<String> existing = category(userId, name, direction);
    if (existing.isPresent()) return existing.get();
    if (Category.labels().stream().anyMatch(l -> l.equalsIgnoreCase(name)) || Category.UNCATEGORIZED.equalsIgnoreCase(name)) {
      throw new IllegalArgumentException("\"" + name + "\" is a built-in category for the other direction");
    }
    lists.addCategory(userId, name, direction);
    return name;
  }

  public Banks banks(long userId) {
    List<String> all = new ArrayList<>(knownBanks);
    all.addAll(lists.banks(userId));
    return new Banks(all);
  }

  /** Adds a bank (or returns the known one it already matches). */
  public String addBank(long userId, String rawName) {
    String name = cleanName(rawName);
    Optional<String> known = banks(userId).resolve(name);
    if (known.isPresent()) return known.get();
    if (Banks.key(name).isEmpty()) throw new IllegalArgumentException("Give the bank a name, e.g. Axis Bank");
    lists.addBank(userId, name);
    return name;
  }

  private static String cleanName(String raw) {
    String name = raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
    if (name.isEmpty() || name.length() > MAX_NAME || !name.matches(".*\\p{L}.*")) {
      throw new IllegalArgumentException("Name must be 1-" + MAX_NAME + " characters and include a letter");
    }
    return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
  }
}
