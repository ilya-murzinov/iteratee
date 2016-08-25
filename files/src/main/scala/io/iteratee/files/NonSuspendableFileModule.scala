package io.iteratee.files

import cats.{ Eval, MonadError }
import io.iteratee.{ Enumerator, Module }
import io.iteratee.internal.Step
import java.io.{
  BufferedInputStream,
  BufferedReader,
  Closeable,
  File,
  FileInputStream,
  FileReader,
  InputStream,
  InputStreamReader
}
import java.util.zip.{ ZipEntry, ZipFile }
import scala.Predef.genericArrayOps
import scala.collection.JavaConverters._
import scala.util.{ Left, Right }

/**
 * File operations for contexts that are not suspendable.
 */
trait NonSuspendableFileModule[F[_]] extends FileModule[F] {
    this: Module[F] { type M[f[_]] <: MonadError[f, Throwable] } =>
  private[this] def captureEffect[A](a: => A): Eval[F[A]] = Eval.always(F.catchNonFatal(a))
  private[this] def close(c: Closeable): Eval[F[Unit]] = Eval.later(F.catchNonFatal(c.close()))

  final def readLines(file: File): Enumerator[F, String] =
    Enumerator.liftMEval(captureEffect(new BufferedReader(new FileReader(file))))(F).flatMap { reader =>
      new LineEnumerator(reader).ensureEval(close(reader))(F)
    }(F)

  final def readLinesFromStream(stream: InputStream): Enumerator[F, String] =
    Enumerator.liftM(F.catchNonFatal(new BufferedReader(new InputStreamReader(stream))))(F).flatMap { reader =>
      new LineEnumerator(reader).ensureEval(close(reader))(F)
    }(F)

  final def readBytes(file: File): Enumerator[F, Array[Byte]] =
    Enumerator.liftM(F.catchNonFatal(new BufferedInputStream(new FileInputStream(file))))(F).flatMap { stream =>
      new ByteEnumerator(stream).ensureEval(close(stream))(F)
    }(F)

  final def readBytesFromStream(stream: InputStream): Enumerator[F, Array[Byte]] =
    Enumerator.liftM(F.catchNonFatal(new BufferedInputStream(stream)))(F).flatMap { stream =>
      new ByteEnumerator(stream).ensureEval(close(stream))(F)
    }(F)

  final def readZipStreams(file: File): Enumerator[F, (ZipEntry, InputStream)] =
    Enumerator.liftMEval(captureEffect(new ZipFile(file)))(F).flatMap { zipFile =>
      new ZipFileEnumerator(zipFile, zipFile.entries.asScala).ensureEval(close(zipFile))(F)
    }(F)

  final def listFiles(dir: File): Enumerator[F, File] =
    Enumerator.liftMEval(captureEffect(dir.listFiles))(F).flatMap {
      case null => Enumerator.empty[F, File](F)
      case files => Enumerator.enumVector(files.toVector)(F)
    }(F)

  final def listFilesRec(dir: File): Enumerator[F, File] = listFiles(dir).flatMap {
    case item if item.isDirectory => listFilesRec(item)
    case item => Enumerator.enumOne(item)(F)
  }(F)

  private[this] final class LineEnumerator(reader: BufferedReader) extends Enumerator[F, String] {
    final def apply[A](s: Step[F, String, A]): F[Step[F, String, A]] = F.tailRecM(s) { step =>
      if (step.isDone) F.pure(Right(step)) else F.flatMap(F.catchNonFatal(reader.readLine())) {
        case null => F.pure(Right(step))
        case line => F.map(step.feedEl(line))(Left(_))
      }
    }
  }

  private[this] final class ByteEnumerator(stream: InputStream, bufferSize: Int = 8192)
      extends Enumerator[F, Array[Byte]] {
    final def apply[A](s: Step[F, Array[Byte], A]): F[Step[F, Array[Byte], A]] = F.tailRecM(s) { step =>
      if (step.isDone) F.pure(Right(step)) else F.flatten(
        F.catchNonFatal {
          val array = new Array[Byte](bufferSize)
          val bytesRead = stream.read(array, 0, bufferSize)
          val read = if (bytesRead == bufferSize) array else array.slice(0, bytesRead)

          if (bytesRead == -1) F.pure(Right(step)) else F.map(step.feedEl(read))(Left(_))
        }
      )
    }
  }

  private[this] final class ZipFileEnumerator(zipFile: ZipFile, iterator: Iterator[ZipEntry])
      extends Enumerator[F, (ZipEntry, InputStream)] {
    final def apply[A](s: Step[F, (ZipEntry, InputStream), A]): F[Step[F, (ZipEntry, InputStream), A]] =
      F.tailRecM(s) { step =>
        if (step.isDone) F.pure(Right(step)) else F.flatten(
          F.catchNonFatal(
            if (iterator.hasNext) {
              val entry = iterator.next

              F.map(step.feedEl((entry, zipFile.getInputStream(entry))))(Left(_))
            } else F.pure(Right(step))
          )
        )
      }
  }
}

object NonSuspendableFileModule {
  private[this] class FromMonadError[F[_]](F0: MonadError[F, Throwable]) extends Module[F]
      with NonSuspendableFileModule[F] {
    type M[F[T]] = MonadError[F, Throwable]
    def F: MonadError[F, Throwable] = F0
  }

  /**
   * Create a [[FileModule]] using the default implementation of `captureEffect`.
   */
  def apply[F[_]](implicit F: MonadError[F, Throwable]): FileModule[F] = new FromMonadError[F](F)
}