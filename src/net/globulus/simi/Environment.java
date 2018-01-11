package net.globulus.simi;

import net.globulus.simi.api.SimiEnvironment;
import net.globulus.simi.api.SimiValue;

import java.util.HashMap;
import java.util.Map;

class Environment implements SimiEnvironment {

  final Environment enclosing;
  private final Map<String, SimiValue> values = new HashMap<>();

  Environment() {
    enclosing = null;
  }

  Environment(Environment enclosing) {
    this.enclosing = enclosing;
  }

  boolean has(String key) {
    return values.containsKey(key);
  }

  SimiValue get(Token name) {
    if (values.containsKey(name.lexeme)) {
      return values.get(name.lexeme);
    }

    if (enclosing != null) return enclosing.get(name);

    throw new RuntimeError(name,
        "Undefined variable '" + name.lexeme + "'.");
  }

  void assign(Token name, SimiValue value, boolean allowImmutable) {
      String key = name.lexeme;
      if (values.get(key) != null) {
        if (allowImmutable || key.startsWith(Constants.MUTABLE)) {
            values.put(key, value);
        } else {
            throw new RuntimeError(name, "Cannot assign to a const, use " + Constants.MUTABLE + " at the start of var name!");
        }
      } else {
          define(key, value);
      }

//    if (enclosing != null) {
//      enclosing.assign(name, value);
//      return;
//    }
//
//    throw new RuntimeError(name,
//        "Undefined variable '" + key + "'.");
  }

  @Override
  public void define(String name, SimiValue value) {
    values.put(name, value);
  }

  Environment ancestor(int distance) {
    Environment environment = this;
    for (int i = 0; i < distance; i++) {
      environment = environment.enclosing; // [coupled]
    }

    return environment;
  }

  SimiValue getAt(int distance, String name) {
    return ancestor(distance).values.get(name);
  }

  void assignAt(int distance, Token name, SimiValue value) {
    ancestor(distance).assign(name, value, false);
  }

  SimiValue tryGet(String name) {
    for (Environment env = this; env != null; env = env.enclosing) {
      SimiValue value = env.values.get(name);
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  @Override
  public String toString() {
    String result = values.toString();
    if (enclosing != null) {
      result += " -> " + enclosing.toString();
    }

    return result;
  }
}
