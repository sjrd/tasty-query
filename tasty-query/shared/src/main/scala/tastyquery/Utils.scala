package tastyquery

private[tastyquery] object Utils:
  /** A memoized computation `computed`, stored in `memo` using the `store` setter. */
  inline def memoized[A](memo: A | Null, inline store: A => Unit)(inline compute: => A): A =
    if memo != null then memo
    else
      // Extracted in a separate def for good jitting of the code calling `memoized`
      def computeAndStore(): A =
        val computed = compute
        store(computed)
        computed
      computeAndStore()
  end memoized

  inline def assignOnce(existing: Any, inline assign: => Unit)(inline msgIfAlreadyAssigned: => String): Unit =
    // Methods calling `assignOnce` are not in fast paths, so no need to extract the exception in a local def
    if existing != null then throw IllegalStateException(msgIfAlreadyAssigned)
    assign

  inline def getAssignedOnce[A](value: A | Null)(inline msgIfNotAssignedYet: => String): A =
    if value != null then value
    else
      // Extracted in a separate def for good jitting of the code calling `getAssignedOnce`
      def notAssignedYet(): Nothing =
        throw IllegalStateException(msgIfNotAssignedYet)
      notAssignedYet()
end Utils
