package is.hail.expr.ir

import java.io.PrintWriter

import is.hail.annotations._
import is.hail.asm4s._
import is.hail.expr.ir.functions.{IRFunctionRegistry, RegistryFunctions, UtilFunctions}
import is.hail.expr.types._
import org.testng.annotations.Test
import is.hail.expr.{EvalContext, FunType, Parser}

import scala.reflect.ClassTag

object ScalaTestObject {
  def testFunction(): Int = 1
}

object ScalaTestCompanion {
  def testFunction(): Int = 2
}

class ScalaTestCompanion {
  def testFunction(): Int = 3
}


object TestRegisterFunctions extends RegistryFunctions {
  def registerAll() {
    registerJavaStaticFunction[java.lang.Integer, Int]("compare", TInt32(), TInt32(), TInt32())("compare")
    registerScalaFunction[Int]("foobar1", TInt32())(ScalaTestObject, "testFunction")
    registerScalaFunction[Int]("foobar2", TInt32())(ScalaTestCompanion, "testFunction")
    registerCode("testCodeUnification", tnum("x"), tv("x", _.isInstanceOf[TInt32]), tv("x")){ (_, a: Code[Int], b: Code[Int]) => a + b }
    registerCode("testCodeUnification2", tv("x"), tv("x")){ (_, a: Code[Long]) => a }
  }
}

class FunctionSuite {

  val ec = EvalContext()
  val region = Region()

  TestRegisterFunctions.registerAll()

  def fromHailString(hql: String): IR = Parser.parseToAST(hql, ec).toIR().get

  def toF[R: TypeInfo](ir: IR): AsmFunction1[Region, R] = {
    Infer(ir)
    val fb = FunctionBuilder.functionBuilder[Region, R]
    Emit(ir, fb)
    fb.result(Some(new PrintWriter(System.out)))()
  }

  def toF[A: TypeInfo, R: TypeInfo](ir: IR): AsmFunction3[Region, A, Boolean, R] = {
    Infer(ir)
    val fb = FunctionBuilder.functionBuilder[Region, A, Boolean, R]
    Emit(ir, fb)
    fb.result(Some(new PrintWriter(System.out)))()
  }

  def lookup(meth: String, types: Type*)(irs: IR*): IR = {
    IRFunctionRegistry.lookupFunction(meth, types).get(irs)
  }

  @Test
  def testCodeFunction() {
    val ir = MakeStruct(Seq(("x", lookup("triangle", TInt32())(In(0, TInt32())))))
    val f = toF[Int, Long](ir)
    val off = f(region, 5, false)
    val expected = (5 * (5 + 1)) / 2
    val actual = region.loadInt(TStruct("x"-> TInt32()).loadField(region, off, 0))
    assert(actual == expected)
  }

  @Test
  def testStaticFunction() {
    val ir = lookup("compare", TInt32(), TInt32())(In(0, TInt32()), I32(0))
    val f = toF[Int, Int](ir)
    val actual = f(region, 5, false)
    assert(actual > 0)
  }

  @Test
  def testScalaFunction() {
    val ir = lookup("foobar1")()
    val f = toF[Int](ir)
    val actual = f(region)
    assert(actual == 1)
  }

  @Test
  def testScalaFunctionCompanion() {
    val ir = lookup("foobar2")()
    val f = toF[Int](ir)
    val actual = f(region)
    assert(actual == 2)
  }


  @Test
  def testUnifySize() {
    val ir = lookup("size", TArray(TInt32()))(In(0, TArray(TInt32())))
    val f = toF[Long, Int](ir)
    val rvb = new RegionValueBuilder(region)
    rvb.start(TArray(TInt32()))
    rvb.addAnnotation(TArray(TInt32()), IndexedSeq(0, 1, 2, 3))
    val actual = f(region, rvb.end(), false)
    assert(actual == 4)
  }

  @Test
  def testVariableUnification() {
    assert(IRFunctionRegistry.lookupFunction("testCodeUnification", Seq(TInt32(), TInt32())).isDefined)
    assert(IRFunctionRegistry.lookupFunction("testCodeUnification", Seq(TInt64(), TInt32())).isEmpty)
    assert(IRFunctionRegistry.lookupFunction("testCodeUnification", Seq(TInt64(), TInt64())).isEmpty)
    assert(IRFunctionRegistry.lookupFunction("testCodeUnification2", Seq(TArray(TInt32()))).isDefined)
  }
}