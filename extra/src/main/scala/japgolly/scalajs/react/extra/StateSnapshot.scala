package japgolly.scalajs.react.extra

import japgolly.scalajs.react._
import japgolly.scalajs.react.internal.{Iso, Lens}

final class StateSnapshot[S](val value: S,
                             val setState: S ~=> Callback,
                             private[StateSnapshot] val reusability: Reusability[S]) {

  override def toString = s"StateSnapshot($value)"

  def modState(f: S => S): Callback =
    setState.value(f(value))

  /** THIS WILL VOID REUSABILITY.
    *
    * The resulting `StateSnapshot[T]` will not be reusable.
    */
  def xmapState[T](f: S => T)(g: T => S): StateSnapshot[T] =
    StateSnapshot(f(value))(setState.value compose g)

  /** THIS WILL VOID REUSABILITY.
    *
    * The resulting `StateSnapshot[T]` will not be reusable.
    */
  def zoomState[T](f: S => T)(g: T => S => S): StateSnapshot[T] =
    xmapState(f)(g(_)(value))

  def withReuse: StateSnapshot.InstanceMethodsWithReuse[S] =
    new StateSnapshot.InstanceMethodsWithReuse(this)
}

object StateSnapshot {

  final class InstanceMethodsWithReuse[S](self: StateSnapshot[S]) { // not AnyVal, nominal for Monocle ext
    import self.{value, setState}

    def xmapState[T](iso: Reusable[(S => T, T => S)]): StateSnapshot[T] =
      new StateSnapshot[T](
        iso._1(value),
        Reusable.ap(setState, iso)((f, g) => f compose g._2),
        self.reusability.contramap(iso._2))

    def zoomState[T](lens: Reusable[(S => T, T => S => S)]): StateSnapshot[T] =
      new StateSnapshot[T](
        lens._1(value),
        Reusable.ap(setState, lens)((f, g) => (t: T) => f(g._2(t)(value))),
        self.reusability.contramap(lens._2(_)(value)))
  }

  // ███████████████████████████████████████████████████████████████████████████████████████████████████████████████████
  // Construction DSL

  object withReuse {

    // Putting (implicit r: Reusability[S]) here would shadow WithReuse.apply
    def apply[S](value: S): FromValue[S] =
      new FromValue(value)

    /** This is meant to be called once and reused so that the setState callback stays the same. */
    def prepare[S](f: S => Callback): FromSetStateFn[S] =
      new FromSetStateFn(Reusable.fn(f))

    /** This is meant to be called once and reused so that the setState callback stays the same. */
    def prepareVia[I, S](i: I)(implicit t: StateAccessor.WritePure[I, S]): FromSetStateFn[S] =
      prepare(t.write(i).setState(_))

    def xmap[S, T](get: S => T)(set: T => S): FromLens[S, T] =
      new FromLens(Iso(get)(set).toLens)

    def zoom[S, T](get: S => T)(set: T => S => S): FromLens[S, T] =
      new FromLens(Lens(get)(set))

    final class FromLens[S, T](private val l: Lens[S, T]) extends AnyVal {
      // There's no point having (value: S)(mod: (S => S) ~=> Callback) because the callback will be composed with the
      // lens which avoids reusability.
      // def apply(value: S) = new FromLensValue(l, l get value)

      /** This is meant to be called once and reused so that the setState callback stays the same. */
      def prepare(modify: (S => S) => Callback): FromLensSetStateFn[S, T] =
        new FromLensSetStateFn(l, Reusable.fn(modify compose l.set))

      /** This is meant to be called once and reused so that the setState callback stays the same. */
      def prepareVia[I](i: I)(implicit t: StateAccessor.WritePure[I, S]): FromLensSetStateFn[S, T] =
        prepare(t.write(i).modState(_))

      def xmap[U](get: T => U)(set: U => T): FromLens[S, U] =
        new FromLens(l --> Iso(get)(set))

      def zoom[U](get: T => U)(set: U => T => T): FromLens[S, U] =
        new FromLens(l --> Lens(get)(set))
    }

    final class FromValue[S](private val value: S) extends AnyVal {
      def apply(set: S ~=> Callback)(implicit r: Reusability[S]): StateSnapshot[S] =
        new StateSnapshot(value, set, r)
    }

    final class FromSetStateFn[S](private val set: S ~=> Callback) extends AnyVal {
      def apply(value: S)(implicit r: Reusability[S]): StateSnapshot[S] =
        withReuse(value)(set)(r)
    }

    final class FromLensSetStateFn[S, T](l: Lens[S, T], set: T ~=> Callback) {
      def apply(value: S)(implicit r: Reusability[T]): StateSnapshot[T] =
        withReuse(l get value)(set)(r)
    }
  }

  // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  // withoutReuse is the default and needn't be specified explicitly

  import withoutReuse._

  def apply[S](value: S): FromValue[S] =
    new FromValue(value)

  def of[I, S](i: I)(implicit t: StateAccessor.ReadImpureWritePure[I, S]): StateSnapshot[S] =
    apply(t.state(i)).setStateVia(i)

  def xmap[S, T](get: S => T)(set: T => S): FromLens[S, T] =
    new FromLens(Iso(get)(set).toLens)

  def zoom[S, T](get: S => T)(set: T => S => S): FromLens[S, T] =
    new FromLens(Lens(get)(set))

  object withoutReuse {
    final class FromLens[S, T](private val l: Lens[S, T]) extends AnyVal {
      def apply(value: S) = new FromLensValue(l, l get value)

      def of[I](i: I)(implicit t: StateAccessor.ReadImpureWritePure[I, S]): StateSnapshot[T] =
        apply(t.state(i)).setStateVia(i)

      def xmap[U](get: T => U)(set: U => T): FromLens[S, U] =
        new FromLens(l --> Iso(get)(set))

      def zoom[U](get: T => U)(set: U => T => T): FromLens[S, U] =
        new FromLens(l --> Lens(get)(set))
    }

    final class FromValue[S](private val value: S) extends AnyVal {
      def apply(set: S => Callback): StateSnapshot[S] =
        new StateSnapshot(value, Reusable.fn(set), Reusability.never)

      def setStateVia[I](i: I)(implicit t: StateAccessor.WritePure[I, S]): StateSnapshot[S] =
        apply(t.write(i).setState(_))
    }

    final class FromLensValue[S, T](l: Lens[S, T], value: T) {
      def apply(modify: (S => S) => Callback): StateSnapshot[T] =
        StateSnapshot(value)(modify compose l.set)

      def setStateVia[I](i: I)(implicit t: StateAccessor.WritePure[I, S]): StateSnapshot[T] =
        apply(t.write(i).modState(_))
    }
  }

  // ███████████████████████████████████████████████████████████████████████████████████████████████████████████████████

  private[this] val reusabilityInstance: Reusability[StateSnapshot[Any]] = {
    val f = implicitly[Reusability[Any ~=> Callback]] // Safe to reuse
    Reusability((x, y) =>
      (x eq y) ||
        (f.test(x.setState, y.setState)
          && x.reusability.test(x.value, y.value)
          && y.reusability.test(x.value, y.value)))
  }

  implicit def reusability[S]: Reusability[StateSnapshot[S]] =
    reusabilityInstance.asInstanceOf[Reusability[StateSnapshot[S]]]
}
