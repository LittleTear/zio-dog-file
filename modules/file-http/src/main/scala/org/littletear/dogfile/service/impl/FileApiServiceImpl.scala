package org.littletear.dogfile.service.impl

import com.github.tototoshi.csv.{CSVWriter, DefaultCSVFormat}
import org.littletear.dogfile.service.{FileApiService, PostRequest}
import zio._
import org.littletear.dogfile.domain.{Env, FileForm, FileSchema, FileSchemaBody, ModelServeBody, UploadResult}
import org.littletear.dogfile.util.{DateUtil, UuidUtil}
import purecsv.config.Headers
import purecsv.safe._
import java.io.File
import scala.util.{Failure, Success}
import io.circe.syntax._
import org.littletear.dogfile.config.Config


case class FileApiServiceImpl(postReq: PostRequest,config: Config) extends FileApiService[FileForm]{
  override def uploadFile(fileForm: FileForm): UIO[UploadResult] =
    for {
      _ <- ZIO.logInfo("got a file")
      p = fileForm.fileField
      fileName = p.fileName.fold("unknown-filename")(identity)
      file = p.body
//      tp =(fileName, file)
//      _ <- Some(tp).fold(ZIO.logError("no file (maybe unprocessable filename?)"))((saveFile _).tupled)
      filePath       <- saveFile(fileName,file)
      _              <- ZIO.logInfo(s"get file path with $filePath")
    } yield UploadResult("file upload success", filePath.replaceAll("\\\\","/"))


  private def saveFile(fileName: String, f: File): ZIO[Any, Nothing, String] = ZIO.succeed {
    import os._
    val sourceFile = Path(f)
    val destDir = os.pwd / "uploaded" / DateUtil.getDayDateString / DateUtil.getHourDateString.replace(":","-") / UuidUtil.getUuid32
    val destFile = destDir / fileName
    os.makeDir.all(destDir)
    os.copy(sourceFile, destFile)
    destFile.toString()
  }

  //加载文件
  private def readFile(filePath:String): ZIO[Any, Nothing, List[FileSchema]] = {
    ZIO.succeed{
      CSVReader[FileSchema]
        .readCSVFromFileName(filePath, headers = Headers.ReadAndIgnore)
        .map {
          case Success(value) => value
          case Failure(exception) => FileSchema("", "")
        }
    }
  }


    //处理数据
    def processFile(contents:List[FileSchema],outPath:String, model:String): ZIO[Any, Throwable, List[Unit]] = {
      ZIO.foreach(contents) {
        fileSchema =>
          val text = fileSchema.question
          val env = Env("596324876360380416", model, "256096", "skill_session:256096:skill.knowledge_graph:1661915707514")
          val serve_body = ModelServeBody(text, env).asJson
          val taoUrl = config.url_file_path.tao_ge_url
          val taoParams = Map[String,String]("vin" -> "BATCH00000001","text" -> text,"model" -> model)

          for {
            //调用模型服务接口
            _                <- ZIO.logInfo(s"start post $text to model serve")
            modelResultFiber <- postReq.post(config.url_file_path.mode_serve_url, serve_body).fork
            //调用涛哥接口
            _ <- ZIO.logInfo(s"start post  $text to taoge serve")
            taoResultFiber <- postReq.post(taoUrl,taoParams).fork
            //写入数据
            modelResult <- modelResultFiber.join
            _ <- ZIO.logInfo(s"modelResult is ${StringContext.processEscapes(modelResult.trim)}")
            taoResult <- taoResultFiber.join
            _ <- ZIO.logInfo(s"taoResult is $taoResult")
            _ <- ZIO.logInfo("start writing a row on file")
            _ <- writeFile[String](Seq(fileSchema.area,text,taoResult,StringContext.processEscapes(modelResult.trim)),outPath)
          } yield ()
      }
    }


  implicit object MyFormat extends DefaultCSVFormat {
    override val delimiter = '|'
    override val quoteChar = ' '
  }
  private def writeFile[T](value:Seq[T],outPath:String = config.url_file_path.output_data_path): Task[Unit] = {
    ZIO.attempt{
      val writer = CSVWriter.open(outPath, append = true, encoding = "UTF-8")
      writer.writeRow(value)
      writer.close()
    }
    }



  override def analyzeFile(filePath:String = config.url_file_path.input_data_path, model:String = "b16"): ZIO[Any, Throwable, Unit] = {
    for{
      _         <- ZIO.logInfo("start read file")
      file      <- readFile(filePath)
      _         <- ZIO.logInfo(s"file content is ${file.take(5).mkString(",")} ......")
      _         <- ZIO.logInfo("start analyze file content")
      _         <- processFile(file,filePath.substring(0,filePath.lastIndexOf("/") + 1) + "result.txt",model)
    } yield ()
  }

  }






object FileApiServiceImpl{
  lazy val live: ZLayer[PostRequest with Config, Nothing, FileApiServiceImpl] = ZLayer.fromFunction(FileApiServiceImpl.apply _)
}
