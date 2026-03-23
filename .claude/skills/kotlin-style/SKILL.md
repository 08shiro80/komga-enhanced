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
- Expression body functions (`fun foo() = ...`) CANNOT contain `return` statements
- If you need `return`, use block body:
```kotlin
// WRONG — return in expression body
fun isValid(): Boolean =
  try {
    if (x) return false  // COMPILE ERROR
    true
  } catch (_: Exception) { false }

// CORRECT — block body
fun isValid(): Boolean {
  return try {
    if (x) return false
    true
  } catch (_: Exception) { false }
}
```

## Exceptions
- Never throw `RuntimeException`, `Exception`, `Throwable`, `Error` directly
- Use specific exception types

## Reverting/Removing Code
- When removing or reverting code blocks, check that all variables defined in the removed block are either:
  - Not referenced elsewhere, OR
  - Re-added at their original location
- Compile errors like `Unresolved reference` often come from removing a `val` that is still used later
