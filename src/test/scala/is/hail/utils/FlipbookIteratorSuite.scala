package is.hail.utils

import is.hail.SparkSuite
import org.testng.annotations.Test
import scala.collection.generic.Growable
import scala.collection.mutable.ArrayBuffer

class FlipbookIteratorSuite extends SparkSuite {

  class Box[A] extends AnyRef {
    var value: A = _

    def canEqual(a: Any): Boolean = a.isInstanceOf[Box[A]]
    override def equals(that: Any): Boolean =
      that match {
        case that: Box[A] => value == that.value
        case _ => false
      }

  }

  object Box {
    def apply[A](): Box[A] = new Box
    def apply[A](a: A): Box[A] = {
      val box = Box[A]()
      box.value = a
      box
    }
  }

  def boxOrdView[A](implicit ord: Ordering[A]): OrderingView[Box[A]] = new OrderingView[Box[A]] {
    var value: A = _
    def setFiniteValue(a: Box[A]) {
      value = a.value
    }
    def compareFinite(a: Box[A]): Int = {
      ord.compare(value, a.value)
    }
  }

  def boxBuffer[A]: Growable[Box[A]] with Iterable[Box[A]] =
    new Growable[Box[A]] with Iterable[Box[A]] {
      val buf = ArrayBuffer[A]()
      val box = Box[A]()
      def clear() { buf.clear() }
      def +=(x: Box[A]) = {
        buf += x.value
        this
      }
      def iterator: Iterator[Box[A]] = new Iterator[Box[A]] {
        var i = 0
        def hasNext = i < buf.size
        def next() = {
          box.value = buf(i)
          i += 1
          box
        }
      }
    }

  def boxIntOrd: (Box[Int], Box[Int]) => Int =
    (l, r) => l.value - r.value

  def makeTestIterator[A](elems: A*): StagingIterator[Box[A]] = {
    val it = elems.iterator
    val sm = new StateMachine[Box[A]] {
      val value: Box[A] = Box()
      var isValid = true
      def advance() {
        if (it.hasNext)
          value.value = it.next()
        else
          isValid = false
      }
    }
    sm.advance()
    StagingIterator(sm)
  }

  implicit class RichTestIterator(it: FlipbookIterator[Box[Int]]) {
    def shouldBe(that: Iterator[Int]): Boolean =
      it.sameElementsUsing(that, (box: Box[Int], int: Int) => box.value == int)
  }

  implicit class RichTestIteratorIterator(
      it: FlipbookIterator[FlipbookIterator[Box[Int]]]) {
    def shouldBe(that: Iterator[Iterator[Int]]): Boolean =
      it.sameElementsUsing(
        that,
        (flipIt: FlipbookIterator[Box[Int]], it: Iterator[Int]) =>
          flipIt shouldBe it)
  }

  implicit class RichTestIteratorMuple(
      it: FlipbookIterator[Muple[Box[Int], Box[Int]]]) {
    def shouldBe(that: Iterator[(Int, Int)]): Boolean =
      it.sameElementsUsing(
        that,
        (muple: Muple[Box[Int], Box[Int]], pair: (Int, Int)) =>
          (muple._1.value == pair._1) && (muple._2.value == pair._2)
      )
  }

  implicit class RichTestIteratorMupleIterator(
    it: FlipbookIterator[Muple[FlipbookIterator[Box[Int]],
                               FlipbookIterator[Box[Int]]]]) {
    def shouldBe(that: Iterator[(Iterator[Int], Iterator[Int])]): Boolean =
      it.sameElementsUsing(
        that,
        (muple: Muple[FlipbookIterator[Box[Int]], FlipbookIterator[Box[Int]]],
         pair: (Iterator[Int], Iterator[Int])) =>
          muple._1.shouldBe(pair._1) && muple._2.shouldBe(pair._2)
      )
  }

  @Test def flipbookIteratorStartsWithRightValue() {
    val it: FlipbookIterator[Box[Int]] =
      makeTestIterator(1, 2, 3, 4, 5)
    assert(it.value.value == 1)
  }

  @Test def makeTestIteratorWorks() {
    assert(makeTestIterator(1, 2, 3, 4, 5) shouldBe Iterator.range(1, 6))

    assert(makeTestIterator[Int]() shouldBe Iterator.empty)
  }

  @Test def toFlipbookIteratorOnFlipbookIteratorIsIdentity() {
    val it1 = makeTestIterator(1, 2, 3)
    val it2 = Iterator(1, 2, 3)
    assert(it1.toFlipbookIterator shouldBe it2)
    assert(makeTestIterator[Int]().toFlipbookIterator shouldBe Iterator.empty)
  }

  @Test def toStaircaseWorks() {
    val testIt = makeTestIterator(1, 1, 2, 3, 3, 3)
    val it = Iterator(
      Iterator(1, 1),
      Iterator(2),
      Iterator(3, 3, 3))
    assert(testIt.staircased(boxOrdView) shouldBe it)
  }

  @Test def orderedZipJoinWorks() {
    val left = makeTestIterator(1, 2, 4)
    val right = makeTestIterator(2, 3, 4)
    val zipped = left.orderedZipJoin(
      right,
      Box(0),
      Box(0),
      boxIntOrd)

    val it = Iterator((1, 0), (2, 2), (0, 3), (4, 4))

    assert(zipped shouldBe it)
  }

  @Test def innerJoinDistinctWorks() {
    val left = makeTestIterator(1, 2, 2, 4)
    val right = makeTestIterator(2, 4, 4, 5)
    val joined = left.innerJoinDistinct(
      right,
      boxOrdView[Int],
      boxOrdView[Int],
      Box(0),
      Box(0),
      boxIntOrd
    )

    val it = Iterator((2, 2), (2, 2), (4, 4))
    assert(joined shouldBe it)
  }

  @Test def leftJoinDistinctWorks() {
    val left = makeTestIterator(1, 2, 2, 4)
    val right = makeTestIterator(2, 4, 4, 5)
    val joined = left.leftJoinDistinct(
      right,
      boxOrdView[Int],
      boxOrdView[Int],
      Box(0),
      Box(0),
      boxIntOrd
    )

    val it = Iterator((1, 0), (2, 2), (2, 2), (4, 4))
    assert(joined shouldBe it)
  }

  @Test def innerJoinWorks() {
    val left = makeTestIterator(1, 2, 2, 4, 5, 5)
    val right = makeTestIterator(2, 2, 4, 4, 5, 6)
    val joined = left.innerJoin(
      right,
      boxOrdView[Int],
      boxOrdView[Int],
      Box(0),
      Box(0),
      boxBuffer[Int],
      boxIntOrd
    )

    val it = Iterator((2, 2), (2, 2), (2, 2), (2, 2), (4, 4), (4, 4), (5, 5), (5, 5))
    assert(joined shouldBe it)
  }

  @Test def leftJoinWorks() {
    val left = makeTestIterator(1, 2, 2, 4, 5, 5)
    val right = makeTestIterator(2, 2, 4, 4, 5, 6)
    val joined = left.leftJoin(
      right,
      boxOrdView[Int],
      boxOrdView[Int],
      Box(0),
      Box(0),
      boxBuffer[Int],
      boxIntOrd
    )

    val it = Iterator((1, 0), (2, 2), (2, 2), (2, 2), (2, 2), (4, 4), (4, 4), (5, 5), (5, 5))
    assert(joined shouldBe it)
  }

  @Test def rightJoinWorks() {
    val left = makeTestIterator(1, 2, 2, 4, 5, 5)
    val right = makeTestIterator(2, 2, 4, 4, 5, 6)
    val joined = left.rightJoin(
      right,
      boxOrdView[Int],
      boxOrdView[Int],
      Box(0),
      Box(0),
      boxBuffer[Int],
      boxIntOrd
    )

    val it = Iterator((2, 2), (2, 2), (2, 2), (2, 2), (4, 4), (4, 4), (5, 5), (5, 5), (0, 6))
    assert(joined shouldBe it)
  }

  @Test def outerJoinWorks() {
    val left = makeTestIterator(1, 2, 2, 4, 5, 5)
    val right = makeTestIterator(2, 2, 4, 4, 5, 6)
    val joined = left.outerJoin(
      right,
      boxOrdView[Int],
      boxOrdView[Int],
      Box(0),
      Box(0),
      boxBuffer[Int],
      boxIntOrd
    )

    val it = Iterator((1, 0), (2, 2), (2, 2), (2, 2), (2, 2), (4, 4), (4, 4), (5, 5), (5, 5), (0, 6))
    assert(joined shouldBe it)
  }
}
