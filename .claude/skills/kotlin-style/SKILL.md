---
name: kotlin-style
description: Kotlin ktlint code style rules — always apply when writing .kt files
user-invocable: false
---

# Kotlin ktlint Rules (MUST follow when writing ANY .kt code)

## Imports
- Sort lexicographically
- `java`, `javax`, `kotlin` packages go last

## String Templates
- Use `$variable` NOT `${variable}` (no redundant braces)

## Method Chains
- Each `.method()` call on its own line:
```kotlin
// WRONG
val x = foo.bar().baz()

// CORRECT
val x =
  foo
    .bar()
    .baz()
```
- Single short chain on one line is OK: `list.size` or `file.name`
- **Builder chains**: `.` must continue on the SAME object's chain, not start a new line after `)`:
```kotlin
// WRONG — newline before .header after .build()
  .uri { ... }
  .header("Accept", "application/json")

// The `.header()` call belongs to the builder chain started by `.get()`,
// NOT to the lambda result. Keep the chain continuous:
  .uri { uriBuilder ->
    uriBuilder
      .path("/manga")
      .build()
  }.header("Accept", "application/json")
```
- **Nullable chained access** — each `?.` on its own line when multiline:
```kotlin
// WRONG — multiple ?. on one line
val x = a.get("b")?.get("c")?.get("d")?.asText() ?: continue

// CORRECT
val x =
  a.get("b")
    ?.get("c")
    ?.get("d")
    ?.asText()
    ?: continue
```
- **Multiline chain: first `.` must also be on its own line** — when a chain is multiline, the very first `.method()` must start on a new line too:
```kotlin
// WRONG — first .get() on same line as object
val x =
  attrs.get("foo")
    ?.get("bar")
    ?.asText()

// CORRECT — first .get() on new line
val x =
  attrs
    .get("foo")
    ?.get("bar")
    ?.asText()

// WRONG — FQN with chained call on same line
val set = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

// CORRECT
val set =
  java.util.concurrent.ConcurrentHashMap
    .newKeySet<String>()
```

## Multiline Expressions
- Value must start on new line after `=`:
```kotlin
// WRONG
val x = someFunction()
  ?: defaultValue

// CORRECT
val x =
  someFunction()
    ?: defaultValue
```

## Trailing Commas
- Required in multiline parameter lists, argument lists, and when-branches:
```kotlin
data class Foo(
  val a: String,
  val b: Int,  // trailing comma
)
```

## Annotations
- Empty line before annotations on declarations:
```kotlin
val jsonData = mapper.readValue(file)

@Suppress("UNCHECKED_CAST")
val items = jsonData as List<*>
```

## UUID generation
- `java.util.UUID.randomUUID().toString()` must be split:
```kotlin
java.util.UUID
  .randomUUID()
  .toString()
```

## ArchUnit Naming
- No `*Service` suffix on Spring services
- Use: `*Lifecycle`, `*Executor`, `*Importer`, `*Provider`

## Property Naming
- ALL property names must be camelCase, even constants:
```kotlin
// WRONG — ktlint rejects SCREAMING_SNAKE_CASE
private val MANGADEX_DATE_FORMAT = DateTimeFormatter.ofPattern("...")

// CORRECT
private val mangaDexDateFormat = DateTimeFormatter.ofPattern("...")
```
- `const val` in companion object may use UPPER_SNAKE_CASE only for primitive types (`const val MAX_SIZE = 512`)
- Non-const `val` (including Regex, DateTimeFormatter, etc.) MUST be camelCase

## Expression Body vs Block Body
- Expression body functions (`fun foo() = ...`) CANNOT contain `return` statements anywhere inside
- This includes `return` inside `if`, `when`, `try/catch`, or any nested block within the expression body
- If ANY code path needs an early `return`, the function MUST use block body `{ ... }`
- **Common trap**: `= try { ... if (x) return false ... }` — the `try` is expression body, so `return` is illegal
```kotlin
// WRONG — return inside expression body (COMPILE ERROR)
fun isInstalled(): Boolean =
  try {
    val p = ProcessBuilder().start()
    if (!p.waitFor(5, TimeUnit.SECONDS)) {
      return false  // ILLEGAL in expression body!
    }
    p.exitValue() == 0
  } catch (_: Exception) { false }

// CORRECT — block body allows return
fun isInstalled(): Boolean {
  return try {
    val p = ProcessBuilder().start()
    if (!p.waitFor(5, TimeUnit.SECONDS)) {
      return false
    }
    p.exitValue() == 0
  } catch (_: Exception) { false }
}
```

## Empty Blocks
- Empty `catch`, `else`, `finally` blocks must still have newlines inside braces:
```kotlin
// WRONG — ktlint error "Expected a newline after/before '{'/'}'"
} catch (_: Exception) {}

// CORRECT
} catch (_: Exception) {
}
```

## Exceptions
- Never throw `RuntimeException`, `Exception`, `Throwable`, `Error` directly
- Use specific exception types

## Expression Body Fits on One Line
- If a single-expression function body fits on the same line as the signature, keep it on one line
- **ktlint error**: "First line of body expression fits on same line as function signature"
- This applies to delegation calls, simple returns, and any single expression
```kotlin
// WRONG — unnecessarily split (ktlint error!)
fun getCount(id: String): Int? =
  delegate.getCount(id, getDefaultLanguage())

// CORRECT — fits on one line
fun getCount(id: String): Int? = delegate.getCount(id, getDefaultLanguage())

// WRONG
private fun getFoo(): String? =
  config["foo"]?.takeIf { it.isNotBlank() }

// CORRECT
private fun getFoo(): String? = config["foo"]?.takeIf { it.isNotBlank() }
```
- Only split to a new line if the expression genuinely exceeds ~120 chars or is multi-part

## Reverting/Removing Code
- When removing or reverting code blocks, check that all variables defined in the removed block are either:
  - Not referenced elsewhere, OR
  - Re-added at their original location
- Compile errors like `Unresolved reference` often come from removing a `val` that is still used later
