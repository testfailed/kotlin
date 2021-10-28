// DO_NOT_CHECK_SYMBOL_RESTORE
package test

class SomeClass

open class TopLevel<Outer> {
    open inner class Base<T> {
        fun noGeneric() {}
        fun withOuter(): Outer? = null
        fun withOwnAndOuter(t: T): Outer? = null
    }
}

class OtherTopLevel : TopLevel<SomeClass>() {
    inner class Child : Base<SomeClass>()
}

// class: test/OtherTopLevel.Child
