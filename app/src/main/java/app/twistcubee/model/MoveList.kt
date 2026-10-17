package app.twistcubee.model

/** Growable list of packed [Move] ints; avoids boxing and per-move allocations. */
class MoveList(initialCapacity: Int = 32) {
    private var data = IntArray(initialCapacity.coerceAtLeast(4))

    var size = 0
        private set

    val isEmpty: Boolean get() = size == 0

    operator fun get(index: Int): Int = data[index]

    fun add(move: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = move
    }

    fun addAll(moves: IntArray) {
        for (m in moves) add(m)
    }

    fun last(): Int = data[size - 1]

    fun removeLast(): Int = data[--size]

    /** Drops every element at index >= [newSize]. */
    fun truncate(newSize: Int) {
        if (newSize < size) size = newSize.coerceAtLeast(0)
    }

    fun clear() {
        size = 0
    }

    fun setAll(moves: IntArray) {
        if (data.size < moves.size) data = IntArray(moves.size)
        moves.copyInto(data)
        size = moves.size
    }

    fun toIntArray(): IntArray = data.copyOf(size)
}
