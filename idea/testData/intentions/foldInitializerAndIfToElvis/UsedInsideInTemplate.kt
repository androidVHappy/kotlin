// IS_APPLICABLE: false

interface A {
    val s: String
}

fun foo() = Any()

fun test(): String {
    val y = foo()
    <caret>if (y !is A) return "Expected A: $y"
    return y.s
}