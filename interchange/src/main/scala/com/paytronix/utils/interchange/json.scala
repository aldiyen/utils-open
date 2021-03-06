//
// Copyright 2012-2013 Paytronix Systems, Inc.
// All Rights Reserved
//

package com.paytronix.utils.interchange

import scala.collection.mutable.Builder

import net.liftweb.json.JsonAST.{JArray, JBool, JDouble, JField, JInt, JNothing, JNull, JObject, JString, JValue}
import net.liftweb.json.JsonParser.{Parser, BoolVal, DoubleVal, IntVal, NullVal, StringVal, End, OpenArr, CloseArr, OpenObj, FieldStart, CloseObj, Token}
import scalaz.{Id, Lens, LensT}

import com.paytronix.utils.scala.result.{Failed, FailedException, Okay, Result, parameter, tryCatch}

object json {
    /**
     * Consume one entire value (such as string, number, object, array) from the pull parser, transforming it into a JValue.
     * Makes using pull parsing easier, since you can token-wrangle as little as possible and then pull entire JValues
     */
    def pullOneAST(p: Parser): Result[Either[Token, JValue]] =
        tryCatch.value {
            sealed abstract class Frame
            final case class ArrayFrame(builder: Builder[JValue, List[JValue]] = List.newBuilder) extends Frame
            final case class ObjectFrame(builder: Builder[JField, List[JField]] = List.newBuilder, var curField: String = null) extends Frame

            var stack: List[Frame] = Nil
            var done = false
            var result: Either[Token, JValue] = null

            while (!done) {
                def completed(in: JValue): Unit =
                    stack match {
                        case Nil =>
                            result = Right(in)
                            done = true
                        case ArrayFrame(builder) :: _ =>
                            builder += in
                        case (f@ObjectFrame(builder, null)) :: _ =>
                            sys.error("unexpected value " + in + "; expected field name")
                        case (f@ObjectFrame(builder, curField)) :: _ =>
                            builder += JField(f.curField, in)
                            f.curField = null

                    }

                p.nextToken match {
                    case End =>
                        stack match {
                            case Nil =>
                                result = Left(End)
                                done = true
                            case (_: ArrayFrame) :: _ =>
                                sys.error("unexpected end of input in array")
                            case (_: ObjectFrame) :: _ =>
                                sys.error("unexpected end of input in object")
                        }

                    case FieldStart(field) =>
                        stack match {
                            case Nil =>
                                sys.error("unexpected field name at top")
                            case (f@ObjectFrame(_, null)) :: _ =>
                                f.curField = field
                            case ObjectFrame(_, field) :: _ =>
                                sys.error("expected value for field " + field)
                            case (_: ArrayFrame) :: _ =>
                                sys.error("unexpected field name in array")
                        }

                    case OpenObj =>
                        stack ::= ObjectFrame()

                    case OpenArr =>
                        stack ::= ArrayFrame()

                    case CloseObj =>
                        stack match {
                            case Nil =>
                                result = Left(CloseObj)
                                done = true
                            case (f@ObjectFrame(builder, null)) :: rest =>
                                stack = rest
                                completed(JObject(builder.result()))
                            case (f@ObjectFrame(builder, field)) :: _ =>
                                sys.error("unexpected end of object immediately following field " + field)
                            case (_: ArrayFrame) :: _ =>
                                sys.error("unexpected } in array")
                        }

                    case CloseArr =>
                        stack match {
                            case Nil =>
                                result = Left(CloseArr)
                                done = true
                            case ArrayFrame(builder) :: rest =>
                                stack = rest
                                completed(JArray(builder.result()))
                            case (_: ObjectFrame) :: _ =>
                                sys.error("unexpected ] in object")
                        }

                    case StringVal(v) => completed(JString(v))
                    case IntVal(v)    => completed(JInt(v))
                    case DoubleVal(v) => completed(JDouble(v))
                    case BoolVal(v)   => completed(JBool(v))
                    case NullVal      => completed(JNull)
                }
            }

            result match {
                case null =>
                    sys.error("expected result to be populated")
                case other =>
                    other
            }
        }

    // FIXME, does this really belong in interchange?
    object lens {
        val id: Lens[JValue, JValue] = LensT.self

        def field(name: String): Lens[JValue, JValue] =
            LensT.lensg (
                _ match {
                    case JObject(jfields) =>
                        _ match {
                            case JNothing => JObject(jfields.filterNot(_.name == name))
                            case newValue =>
                                JObject(JField(name, newValue) :: jfields.filterNot(_.name == name))
                        }
                    case other =>
                        _ match {
                            case JNothing => other
                            case newValue => JObject(List(JField(name, newValue)))
                        }
                },
                _ \ name
            )

        def path(path: String*): Lens[JValue, JValue] =
            path.foldLeft(id)((l, f) => l >=> field(f))

        val boolean: LensT[Result, JValue, Boolean] =
            LensT.lensgT (
                _ => Okay(b => JBool(b)),
                BooleanCoder.decode(getClass.getClassLoader, _).orElse(parameter(())): Result[Boolean]
            )

        val bigDecimal: LensT[Result, JValue, BigDecimal] =
            LensT.lensgT (
                _ => Okay(bd => JString(bd.toString)),
                ScalaBigDecimalCoder.decode(getClass.getClassLoader, _).orElse(parameter(())): Result[BigDecimal]
            )

        val bigInt: LensT[Result, JValue, BigInt] =
            LensT.lensgT (
                _ => Okay(bi => JInt(bi)),
                BigIntCoder.decode(getClass.getClassLoader, _).orElse(parameter(())): Result[BigInt]
            )

        val byte: LensT[Result, JValue, Byte] =
            LensT.lensgT (
                _ => Okay(b => JInt(b)),
                ByteCoder.decode(getClass.getClassLoader, _).orElse(parameter(())): Result[Byte]
            )

        val double: LensT[Result, JValue, Double] =
            LensT.lensgT (
                _ => Okay(d => JDouble(d)),
                DoubleCoder.decode(getClass.getClassLoader, _).orElse(parameter(())): Result[Double]
            )

        val float: LensT[Result, JValue, Float] =
            LensT.lensgT (
                _ => Okay(f => JDouble(f)),
                FloatCoder.decode(getClass.getClassLoader, _).orElse(parameter(())): Result[Float]
            )

        val int: LensT[Result, JValue, Int] =
            LensT.lensgT (
                _ => Okay(i => JInt(BigInt(i))),
                IntCoder.decode(getClass.getClassLoader, _).orElse(parameter(())): Result[Int]
            )

        val long: LensT[Result, JValue, Long] =
            LensT.lensgT (
                _ => Okay(l => JInt(BigInt(l))),
                LongCoder.decode(getClass.getClassLoader, _).orElse(parameter(())): Result[Long]
            )

        val short: LensT[Result, JValue, Short] =
            LensT.lensgT (
                _ => Okay(s => JInt(BigInt(s))),
                ShortCoder.decode(getClass.getClassLoader, _).orElse(parameter(())): Result[Short]
            )

        val string: LensT[Result, JValue, String] =
            LensT.lensgT (
                _ => Okay(s => JString(s)),
                StringCoder.decode(getClass.getClassLoader, _).orElse(parameter(())): Result[String]
            )

    }
}
