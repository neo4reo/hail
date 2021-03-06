package is.hail.expr.ir

import is.hail.expr.{BaseIR, MatrixRead, TableImport, TableRead}
import is.hail.utils._

object Pretty {

  def apply(ir: BaseIR): String = {
    val sb = new StringBuilder

    def pretty(ir: BaseIR, depth: Int) {
      sb.append(" " * depth)
      sb += '('
      sb.append(ir.getClass.getName.split("\\.").last)

      ir match {
        case MakeStruct(fields, _) =>
          if (fields.nonEmpty) {
            sb += '\n'
            fields.foreachBetween { case (n, a) =>
              sb.append(" " * (depth + 2))
              sb += '('
              sb.append(n)
              sb += '\n'
              pretty(a, depth + 4)
              sb += ')'
            }(sb += '\n')
          }
        case InsertFields(old, fields, _) =>
          sb += '\n'
          pretty(old, depth + 2)
          if (fields.nonEmpty) {
            sb += '\n'
            fields.foreachBetween { case (n, a) =>
              sb.append(" " * (depth + 2))
              sb += '('
              sb.append(n)
              sb += '\n'
              pretty(a, depth + 4)
              sb += ')'
            }(sb += '\n')
          }
        case _ =>
          val header = ir match {
            case I32(x) => x.toString
            case I64(x) => x.toString
            case F32(x) => x.toString
            case F64(x) => x.toString
            case Cast(_, typ) => typ.toString
            case NA(typ) => typ.toString
            case Let(name, _, _, _) => name
            case Ref(name, _) => name
            case ApplyBinaryPrimOp(op, _, _, _) => op.getClass.getName.split("\\.").last
            case ApplyUnaryPrimOp(op, _, _) => op.getClass.getName.split("\\.").last
            case ArrayRef(_, i, _) => i.toString
            case GetField(_, name, _) => name
            case GetTupleElement(_, idx, _) => idx.toString
            case ArrayMap(_, name, _, _) => name
            case ArrayFilter(_, name, _) => name
            case ArrayFlatMap(_, name, _) => name
            case ArrayFold(_, _, accumName, valueName, _, _) => s"$accumName $valueName"
            case Apply(function, _, _) => function
            case ApplySpecial(function, _, _) => function
            case In(i, _) => i.toString
            case MatrixRead(path, _, dropCols, dropRows) =>
              s"$path${ if (dropRows) "drop_rows" else "" }${ if (dropCols) "drop_cols" else "" }"
            case TableImport(paths, _, _) =>
              if (paths.length == 1)
                paths.head
              else {
                sb += '\n'
                sb.append(" " * (depth + 2))
                sb.append("(paths\n")
                paths.foreachBetween { p =>
                  sb.append(" " * (depth + 4))
                  sb.append(p)
                }(sb += '\n')
                sb += ')'

                ""
              }
            case TableRead(path, _, dropRows) =>
              if (dropRows)
                s"$path drop_rows"
              else
                path
            case TableWrite(_, path, overwrite, _) =>
              if (overwrite)
                s"$path overwrite"
              else
                path
            case _ => ""
          }

          if (header.nonEmpty) {
            sb += ' '
            sb.append(header)
          }

          val children = ir.children
          if (children.nonEmpty) {
            sb += '\n'
            children.foreachBetween(c => pretty(c, depth + 2))(sb += '\n')
          }

          sb += ')'
      }
    }

    pretty(ir, 0)

    sb.result()
  }
}
