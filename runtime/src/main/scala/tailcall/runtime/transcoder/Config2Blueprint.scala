package tailcall.runtime.transcoder

import tailcall.runtime.ast.{Blueprint, Endpoint, TSchema}
import tailcall.runtime.dsl.json.Config
import tailcall.runtime.dsl.json.Config._
import tailcall.runtime.http.Method
import tailcall.runtime.remote.Remote
import tailcall.runtime.transcoder.Transcoder.Syntax
import zio.json.ast.Json
import zio.schema.{DynamicValue, Schema}

final case class Config2Blueprint(config: Config) {

  val graphQLSchemaMap: Map[String, Map[String, Field]] = config.graphQL.types

  implicit private def jsonSchema: Schema[Json] =
    Schema[DynamicValue]
      .transformOrFail[Json](a => a.transcodeOrFailWith[Json, String], b => b.transcodeOrFailWith[DynamicValue, String])

  private def toType(field: Field): Blueprint.Type = {
    val ofType = Blueprint.NamedType(field.typeOf, field.isRequired.getOrElse(false))
    val isList = field.isList.getOrElse(false)
    if (isList) Blueprint.ListType(ofType, false) else ofType
  }

  private def toType(inputType: Argument): Blueprint.Type = {
    val ofType = Blueprint.NamedType(inputType.typeOf, inputType.isRequired.getOrElse(false))
    val isList = inputType.isList.getOrElse(false)
    if (isList) Blueprint.ListType(ofType, false) else ofType
  }

  private def toTSchema(field: Field): TSchema = {
    graphQLSchemaMap.get(field.typeOf) match {
      case Some(value) =>
        val schema = TSchema.obj(value.toList.filter(_._2.steps.isEmpty).map { case (fieldName, field) =>
          TSchema.Field(fieldName, toTSchema(field))
        })

        if (field.isList.getOrElse(false)) schema.arr else schema

      case None => field.typeOf match {
          case "String"  => TSchema.string
          case "Int"     => TSchema.int
          case "Boolean" => TSchema.bool
          case _         => TSchema.`null`
        }
    }
  }

  private def toEndpoint(http: Step.Http, host: String): Endpoint =
    Endpoint.make(host).withPort(config.server.port.getOrElse(80)).withPath(http.path)
      .withMethod(http.method.getOrElse(Method.GET)).withInput(http.input).withOutput(http.output)

  private def toRemoteMap(lookup: Remote[DynamicValue], map: Map[String, List[String]]): Remote[DynamicValue] =
    map.foldLeft(Remote(Map.empty[String, DynamicValue])) { case (to, (key, path)) =>
      lookup.path(path: _*).map(value => to.put(Remote(key), value)).getOrElse(to)
    }.toDynamic

  private def toResolver(steps: List[Step], field: Field): Option[Remote[DynamicValue] => Remote[DynamicValue]] =
    steps match {
      case Nil   => None
      case steps => config.server.host match {
          case None if steps.exists(_.isInstanceOf[Step.Http]) => None
          case option                                          => option.map { host =>
              steps.map[Remote[DynamicValue] => Remote[DynamicValue]] {
                case http @ Step.Http(_, _, _, _) => input =>
                    val endpoint           = toEndpoint(http, host)
                    val inferOutput        = steps.indexOf(http) == steps.length - 1 && endpoint.output.isEmpty
                    val endpointWithOutput =
                      if (inferOutput) endpoint.withOutput(Option(toTSchema(field))) else endpoint
                    Remote.fromEndpoint(endpointWithOutput, input)
                case Step.Constant(json)          => _ => Remote(json).toDynamic
                case Step.ObjPath(map)            => input => toRemoteMap(input, map)
              }.reduce((a, b) => r => b(a(r)))
            }
        }
    }

  def toBlueprint: Blueprint = {
    val rootSchema = Blueprint
      .SchemaDefinition(query = config.graphQL.schema.query, mutation = config.graphQL.schema.mutation)

    val definitions: List[Blueprint.Definition] = config.graphQL.types.toList.map { case (name, fields) =>
      val bFields: List[Blueprint.FieldDefinition] = {
        fields.toList.map { case (name, field) =>
          val args: List[Blueprint.InputValueDefinition] = {
            field.args.getOrElse(Map.empty).toList.map { case (name, inputType) =>
              Blueprint.InputValueDefinition(name, toType(inputType), None)
            }
          }

          val ofType = toType(field)

          val resolver = toResolver(field.steps.getOrElse(Nil), field)

          Blueprint.FieldDefinition(name, args, ofType, resolver.map(Remote.toLambda(_)))
        }
      }

      Blueprint.ObjectTypeDefinition(name = name, fields = bFields)
    }

    Blueprint(rootSchema, definitions)
  }

}