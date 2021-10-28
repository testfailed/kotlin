package test

class SomeClass1
class SomeClass2

interface InterfaceWithValBase<T1, T2> {
    val noGenerics_InterfaceWithValBase: SomeClass1

    val withOuterGenericT1_InterfaceWithValBase: T1

    val withOuterGenericT2_InterfaceWithValBase: T2
}

interface InterfaceWithVal<T> : InterfaceWithValBase<SomeClass1, T> {
    val noGenerics_InterfaceWithVal: SomeClass1

    val withOuterGeneric_InterfaceWithVal: T
}


abstract class ClassWithInterfaceWithVal : InterfaceWithVal<SomeClass2>

// class: test/ClassWithInterfaceWithVal