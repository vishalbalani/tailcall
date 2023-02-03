package tailcall.gateway.remote

import zio.Chunk
import zio.schema.meta.MetaSchema
import zio.schema.{DeriveSchema, DynamicValue, Schema}

import java.util.concurrent.atomic.AtomicInteger

sealed trait DynamicEval

object DynamicEval {
  final case class Literal(value: DynamicValue, meta: MetaSchema) extends DynamicEval

  final case class EqualTo(left: DynamicEval, right: DynamicEval, tag: Equatable[Any])
      extends DynamicEval

  final case class Math(operation: Math.Operation, tag: Numeric[Any]) extends DynamicEval
  object Math {
    sealed trait Operation

    final case class Binary(left: DynamicEval, right: DynamicEval, operation: Binary.Operation)
        extends Operation
    object Binary {
      sealed trait Operation
      case object Add      extends Operation
      case object Multiply extends Operation
      case object Divide   extends Operation
      case object Modulo   extends Operation
    }

    case class Unary(value: DynamicEval, operation: Unary.Operation) extends Operation
    object Unary {
      sealed trait Operation
      case object Negate extends Operation
    }

    def apply(
      left: DynamicEval,
      right: DynamicEval,
      operation: Binary.Operation,
      tag: Numeric[Any]
    ): Math = Math(Binary(left, right, operation), tag)

    def apply(value: DynamicEval, operation: Unary.Operation, tag: Numeric[Any]): Math =
      Math(Unary(value, operation), tag)

  }

  final case class Logical(operation: Logical.Operation) extends DynamicEval
  object Logical {
    sealed trait Operation
    final case class Binary(left: DynamicEval, right: DynamicEval, operation: Binary.Operation)
        extends Operation

    object Binary {
      sealed trait Operation
      case object And extends Operation
      case object Or  extends Operation
    }

    final case class Unary(value: DynamicEval, operation: Unary.Operation) extends Operation
    object Unary {
      sealed trait Operation
      case object Not                                               extends Operation
      case class Diverge(isTrue: DynamicEval, isFalse: DynamicEval) extends Operation
    }

    def apply(left: DynamicEval, right: DynamicEval, operation: Binary.Operation): DynamicEval =
      Logical(Binary(left, right, operation))

    def apply(value: DynamicEval, operation: Unary.Operation): DynamicEval =
      Logical(Unary(value, operation))

  }

  final case class StringOperations(operation: StringOperations.Operation) extends DynamicEval
  object StringOperations {
    sealed trait Operation
    final case class Concat(left: DynamicEval, right: DynamicEval) extends Operation
  }

  final case class SeqOperations(operation: SeqOperations.Operation) extends DynamicEval

  // TODO: rename to SeqOperations
  // TODO: Support for other collections
  object SeqOperations {
    sealed trait Operation
    final case class Concat(left: DynamicEval, right: DynamicEval)      extends Operation
    final case class Reverse(seq: DynamicEval)                          extends Operation
    final case class Filter(seq: DynamicEval, condition: EvalFunction)  extends Operation
    final case class FlatMap(seq: DynamicEval, operation: EvalFunction) extends Operation
    final case class Length(seq: DynamicEval)                           extends Operation
    final case class IndexOf(seq: DynamicEval, element: DynamicEval)    extends Operation
    final case class Sequence(value: Chunk[DynamicEval])                extends Operation
  }

  final case class FunctionCall(f: EvalFunction, arg: DynamicEval) extends DynamicEval
  final case class Binding private (id: Int)                       extends DynamicEval
  object Binding {
    private val counter = new AtomicInteger(0)
    def make: Binding   = new Binding(counter.incrementAndGet())
  }
  final case class EvalFunction(input: Binding, body: DynamicEval) extends DynamicEval

  def add(left: DynamicEval, right: DynamicEval, tag: Numeric[Any]): Math =
    Math(left, right, Math.Binary.Add, tag)

  def multiply(left: DynamicEval, right: DynamicEval, tag: Numeric[Any]): Math =
    Math(left, right, Math.Binary.Multiply, tag)

  def divide(left: DynamicEval, right: DynamicEval, tag: Numeric[Any]): Math =
    Math(left, right, Math.Binary.Divide, tag)

  def modulo(left: DynamicEval, right: DynamicEval, tag: Numeric[Any]): Math =
    Math(left, right, Math.Binary.Modulo, tag)

  def negate(value: DynamicEval, tag: Numeric[Any]): Math = Math(value, Math.Unary.Negate, tag)

  def and(left: DynamicEval, right: DynamicEval): DynamicEval =
    Logical(left, right, Logical.Binary.And)

  def or(left: DynamicEval, right: DynamicEval): DynamicEval =
    Logical(left, right, Logical.Binary.Or)

  def not(value: DynamicEval): DynamicEval = Logical(value, Logical.Unary.Not)

  def diverge(cond: DynamicEval, isTrue: DynamicEval, isFalse: DynamicEval): DynamicEval =
    Logical(Logical.Unary(cond, Logical.Unary.Diverge(isTrue, isFalse)))

  def equal(left: DynamicEval, right: DynamicEval, tag: Equatable[Any]): DynamicEval =
    EqualTo(left, right, tag.any)

  def binding: Binding = Binding.make

  def functionCall(f: EvalFunction, arg: DynamicEval): DynamicEval = FunctionCall(f, arg)

  def filter(seq: DynamicEval, condition: EvalFunction): DynamicEval =
    SeqOperations(SeqOperations.Filter(seq, condition))

  def flatMap(seq: DynamicEval, operation: EvalFunction): DynamicEval =
    SeqOperations(SeqOperations.FlatMap(seq, operation))

  def concat(left: DynamicEval, right: DynamicEval): DynamicEval =
    SeqOperations(SeqOperations.Concat(left, right))

  def reverse(seq: DynamicEval): DynamicEval = SeqOperations(SeqOperations.Reverse(seq))

  def length(seq: DynamicEval): DynamicEval = SeqOperations(SeqOperations.Length(seq))

  def indexOf(seq: DynamicEval, element: DynamicEval): DynamicEval =
    SeqOperations(SeqOperations.IndexOf(seq, element))

  implicit val schema: Schema[DynamicEval] = DeriveSchema.gen[DynamicEval]
}