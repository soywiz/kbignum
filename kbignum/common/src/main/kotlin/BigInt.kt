package com.soywiz

import kotlin.math.*

/**
 * @TODO: Use JVM BigInteger and JS BigInt
 */
class BigInt private constructor(val data: UInt16ArrayZeroPad, val signum: Int, var dummy: Boolean) {
	val isSmall get() = data.size <= 1
	val isZero get() = signum == 0
	val isNegative get() = signum < 0
	val isPositive get() = signum > 0
	val isNegativeOrZero get() = signum <= 0
	val isPositiveOrZero get() = signum >= 0
	val maxBits get() = data.size * 16
	val significantBits get() = maxBits - leadingZeros()

	companion object {
		val ZERO = BigInt(uint16ArrayZeroPadOf(), 0, true)
		val MINUS_ONE = BigInt(uint16ArrayZeroPadOf(1), -1, true)
		val ONE = BigInt(uint16ArrayZeroPadOf(1), 1, true)
		val TWO = BigInt(uint16ArrayZeroPadOf(2), 1, true)
		val TEN = BigInt(uint16ArrayZeroPadOf(10), 1, true)

		operator fun invoke(data: UInt16ArrayZeroPad, signum: Int): BigInt {
			// Trim leading zeros
			var maxN = 0
			for (n in data.size - 1 downTo 0) {
				if (data[n] != 0) {
					maxN = n + 1
					break
				}
			}

			if (maxN == 0) return ZERO
			return BigInt(data.copyOf(maxN), signum, false)
		}

		operator fun invoke(value: Long): BigInt {
			if (value.toInt().toLong() == value) return invoke(value.toInt())
			return invoke("$value")
		}

		private fun create(value: Int): BigInt {
			val magnitude = value.toLong().absoluteValue
			if (value == 0) return BigInt(uint16ArrayZeroPadOf(), 0, true)
			return BigInt(uint16ArrayZeroPadOf((magnitude ushr 0).toInt(), (magnitude ushr 16).toInt()), value.sign)
		}

		operator fun invoke(value: Int): BigInt {
			// Optimize by directly using the array
			return when (value) {
				-1 -> MINUS_ONE; 0 -> ZERO; 1 -> ONE; 2 -> TWO
				else -> create(value)
			}
		}

		operator fun invoke(str: String, radix: Int = 10): BigInt {
			if (str == "0") return ZERO
			if (str.startsWith('-')) return -invoke(str.substring(1), radix)
			var out = ZERO
			for (c in str) {
				out *= radix
				out += digit(c)
			}
			return out
		}
	}

	// Optimize
	fun countBits(): Int {
		var out = 0
		for (n in 0 until maxBits) if (getBit(n)) out++
		return out
	}

	fun trailingZeros(): Int {
		if (isZero) return 0
		for (n in 0 until maxBits) if (getBit(n)) return n
		return 0
	}

	fun leadingZeros(): Int {
		if (isZero) return 0
		for (n in 0 until maxBits) if (getBit(maxBits - n - 1)) return n
		return maxBits
	}

	operator fun plus(other: BigInt): BigInt {
		val l = this
		val r = other
		return when {
			l.isZero -> r
			r.isZero -> l
			l.isNegative && r.isPositive -> r - l.absoluteValue
			l.isPositive && r.isNegative -> l - r.absoluteValue
			l.isNegative && r.isNegative -> -(l.absoluteValue + r.absoluteValue)
			else -> BigInt(UnsignedBigInt.add(this.data, other.data), signum)
		}
	}

	operator fun minus(other: BigInt): BigInt {
		val l = this
		val r = other
		return when {
			r.isZero -> l
			l.isZero -> -r
			l.isNegative && r.isNegative -> r.abs() - l.abs() // (-l) - (-r) == (-l) + (r) == (r - l)
			l.isNegative && r.isPositive -> -(l.absoluteValue + r) // -l - r == -(l + r)
			l.isPositive && r.isNegative -> l + r.absoluteValue // l - (-r) == l + r
			l.isPositive && r.isPositive && l < r -> -(r - l)
			else -> BigInt(UnsignedBigInt.sub(l.data, r.data), 1)
		}
	}

	infix fun pow(other: BigInt): BigInt {
		TODO()
	}

	infix fun pow(other: Int): BigInt {
		// Optimize
		var out = this
		for (n in 0 until other) out *= this
		return out
	}

	operator fun times(other: BigInt): BigInt {
		return when {
			this.isZero || other.isZero -> ZERO
			this == ONE -> other
			other == ONE -> this
			this == TWO -> other shl 1
			other == TWO -> this shl 1
			other.countBits() == 1 -> BigInt(
				(this shl other.trailingZeros()).data,
				if (this.signum == other.signum) +1 else -1
			)
			else -> BigInt(UnsignedBigInt.mul(this.data, other.data), if (this.signum == other.signum) +1 else -1)
		}
	}

	operator fun div(other: BigInt): BigInt {
		return when {
			this.isZero -> ZERO
			other.isZero -> error("Division by zero")
			other == ONE -> this
			other == TWO -> this shr 1
			other == TEN -> BigInt(UnsignedBigInt.divRemSmall(this.data, 10).div, this.signum)
			other.countBits() == 1 -> BigInt(
				(this shr other.trailingZeros()).data,
				if (this.signum == other.signum) +1 else -1
			)
			this.signum != other.signum -> -(this.absoluteValue / other.absoluteValue)
			this.isNegative && other.isNegative -> this.absoluteValue / other.absoluteValue
			else -> this.divRemBig(other).div
		}
	}

	// Asumes positive non zero values this > 0 && other > 0
	data class DivRem(val div: BigInt, val rem: BigInt)

	// Simple euclidean division
	fun divRemBig(other: BigInt): DivRem {
		if (this.isZero) return DivRem(ZERO, ZERO)
		if (other.isZero) error("division by zero")
		if (this.isNegative || other.isNegative) error("Non positive numbers")
		val lbits = this.significantBits
		val rbits = other.significantBits
		var rem = this
		var divisor = other
		var divisorShift = 0
		var res = ZERO
		val initialShiftBits = lbits - rbits + 1
		divisorShift += initialShiftBits
		divisor = divisor shl initialShiftBits

		while (divisorShift >= 0) {
			if (divisor.isZero) error("divisor is zero!")

			if (divisor <= rem) {
				res += 1 shl divisorShift
				rem -= divisor
			}
			divisorShift--
			divisor = divisor shr 1
		}

		return DivRem(res, rem)
	}

	operator fun rem(other: BigInt): BigInt {
		if (other.isNegative) return this % other.absoluteValue
		if (other == ZERO) error("Division by zero")
		if (other == ONE) return ZERO
		if (other == TWO) return getBitInt(0).bi
		if (other == TEN) {
			val res = UnsignedBigInt.divRemSmall(this.data, 10)
			val rem = res.rem
			val remN = rem.bi
			return remN
		}
		if (this.isNegative) return -this.absoluteValue.divRemBig(other).rem
		return this.divRemBig(other).rem
	}

	fun getBitInt(n: Int): Int = ((data[n / 16] ushr (n % 16)) and 1)
	fun getBit(n: Int): Boolean = getBitInt(n) != 0

	infix fun shl(count: Int): BigInt {
		if (count < 0) return this shr (-count)
		var out = this
		val blockShift = count / 16
		val smallShift = count % 16
		if (smallShift > 0) out = out.shlSmall(smallShift)
		if (blockShift > 0) out = out.shlBlock(blockShift)
		return out
	}

	infix fun shr(count: Int): BigInt {
		//if (this.isNegative) return -(this.absoluteValue shr count) - 1
		if (count < 0) return this shl (-count)
		var out = this
		val blockShift = count / 16
		val smallShift = count % 16
		if (blockShift > 0) out = out.shrBlock(blockShift)
		if (smallShift > 0) out = out.shrSmall(smallShift)
		return out
	}

	private infix fun shlBlock(count: Int): BigInt {
		val out = UInt16ArrayZeroPad(data.size + count)
		for (n in 0 until data.size) out[n + count] = this.data[n]
		return BigInt(out, signum)
	}

	private infix fun shrBlock(count: Int): BigInt {
		val out = UInt16ArrayZeroPad(data.size - count)
		for (n in count until data.size) out[n - count] = this.data[n]
		return BigInt(out, signum)
	}

	private infix fun shlSmall(count: Int): BigInt {
		val out = UInt16ArrayZeroPad(data.size + 1)
		var carry = 0
		val count_rcp = 16 - count
		for (n in 0 until data.size + 1) {
			val v = data[n]
			out[n] = ((carry) or (v shl count))
			carry = v ushr count_rcp
		}
		if (carry != 0) error("ERROR!")
		return BigInt(out, signum)
	}

	private infix fun shrSmall(count: Int): BigInt {
		val out = UInt16ArrayZeroPad(data.size)
		var carry = 0
		val count_rcp = 16 - count
		val LOW_MASK = (1 shl count) - 1
		for (n in data.size - 1 downTo 0) {
			val v = data[n]
			out[n] = ((carry shl count_rcp) or (v ushr count))
			carry = v and LOW_MASK
		}
		return BigInt(out, signum)
	}

	operator fun compareTo(that: BigInt): Int {
		if (this.isNegative && that.isPositiveOrZero) return -1
		if (this.isPositiveOrZero && that.isNegative) return +1
		val resUnsigned = UnsignedBigInt.compare(this.data, that.data)
		return if (this.isNegative && that.isNegative) -resUnsigned else resUnsigned
	}

	override fun hashCode(): Int = this.data.hashCode() * this.signum
	override fun equals(other: Any?): Boolean =
		(other is BigInt) && this.signum == other.signum && this.data.contentEquals(other.data)

	val absoluteValue get() = abs()
	fun abs() = if (this.isZero) ZERO else BigInt(this.data, 1)
	operator fun unaryPlus(): BigInt = this
	operator fun unaryMinus(): BigInt = BigInt(this.data, -signum, false)

	operator fun plus(other: Int): BigInt = plus(other.bi)
	operator fun minus(other: Int): BigInt = minus(other.bi)
	operator fun times(other: Int): BigInt = times(other.bi)
	operator fun times(other: Long): BigInt = times(other.bi)
	operator fun div(other: Int): BigInt = div(other.bi)
	operator fun rem(other: Int): BigInt = rem(other.bi)

	infix fun and(other: BigInt): BigInt = bitwise(other, Int::and)
	infix fun or(other: BigInt): BigInt = bitwise(other, Int::or)
	infix fun xor(other: BigInt): BigInt = bitwise(other, Int::xor)

	private inline fun bitwise(other: BigInt, op: (a: Int, b: Int) -> Int): BigInt {
		return BigInt(UInt16ArrayZeroPad(max(this.data.size, other.data.size)).also {
			for (n in 0 until it.size) it[n] = op(this.data[n], other.data[n])
		}, 1)
	}

	override fun toString() = toString(10)

	fun toString(radix: Int): String {
		return when (radix) {
			2 -> toString2()
			else -> toStringGeneric(radix)
		}
	}

	fun toString2(): String {
		if (this.isNegative) return "-" + this.abs().toString2()
		var out = ""
		for (n in 0 until maxBits) out += if (getBit(n)) '1' else '0'
		out = out.trimEnd('0')
		if (out.isEmpty()) out = "0"
		return out.reversed()
	}

	private fun toStringGeneric(radix: Int): String {
		if (radix !in 2..26) error("Invalid radix $radix!")
		if (this.isZero) return "0"
		if (this.isNegative) return "-" + this.absoluteValue.toStringGeneric(radix)
		val out = StringBuilder()
		var num = this
		// Optimize with mutable data
		while (num != 0.bi) {
			val result = UnsignedBigInt.divRemSmall(num.data, radix)
			out.append(digit(result.rem))
			num = BigInt(result.div, 1)
		}
		return out.reversed().toString()
	}

	fun toInt(): Int {
		if (significantBits > 31) error("Can't represent BigInt as integer")
		val magnitude = (this.data[0].toLong() or (this.data[1].toLong() shl 16)) * signum
		return magnitude.toInt()
	}

	fun toBigNum(): BigNum = BigNum(this, 0)
}

class UInt16ArrayZeroPad private constructor(val data: IntArray) {
	val size get() = data.size

	constructor(size: Int) : this(IntArray(max(1, size)))

	operator fun get(index: Int) = data.getOrElse(index) { 0 }
	operator fun set(index: Int, value: Int) {
		if (index !in data.indices) return
		data[index] = value and 0xFFFF
	}

	fun contentEquals(other: UInt16ArrayZeroPad) = this.data.contentEquals(other.data)
	fun copyOf(size: Int): UInt16ArrayZeroPad = UInt16ArrayZeroPad(data.copyOf(size))
}

fun uint16ArrayZeroPadOf(vararg values: Int) =
	UInt16ArrayZeroPad(values.size).apply { for (n in 0 until values.size) this[n] = values[n] }

private fun digit(v: Int): Char {
	if (v in 0..9) return '0' + v
	if (v in 10..26) return 'a' + (v - 10)
	error("Invalid digit $v")
}

private fun digit(c: Char): Int {
	return when (c) {
		in '0'..'9' -> c - '0'
		in 'a'..'z' -> c - 'a' + 10
		in 'A'..'Z' -> c - 'A' + 10
		else -> error("Invalid digit '$c'")
	}
}

object UnsignedBigInt {
	internal fun add(l: UInt16ArrayZeroPad, r: UInt16ArrayZeroPad): UInt16ArrayZeroPad {
		var carry = 0
		val out = UInt16ArrayZeroPad(max(l.size, r.size) + 1)
		for (i in 0 until out.size) {
			val sum = l[i] + r[i] + carry
			carry = if ((sum ushr 16) != 0) 1 else 0
			out[i] = sum - (carry shl 16)
		}
		return out
	}

	// l >= 0 && r >= 0 && l >= r
	internal fun sub(l: UInt16ArrayZeroPad, r: UInt16ArrayZeroPad): UInt16ArrayZeroPad {
		var borrow = 0
		val out = UInt16ArrayZeroPad(max(l.size, r.size) + 1)
		for (i in 0 until out.size) {
			val difference = l[i] - borrow - r[i]
			out[i] = difference
			borrow = if (difference < 0) 1 else 0
		}
		return out
	}

	// l >= 0 && r >= 0
	// TODO optimize using the Karatsuba algorithm:
	// TODO: - https://en.wikipedia.org/wiki/Multiplication_algorithm#Karatsuba_multiplication
	internal fun mul(l: UInt16ArrayZeroPad, r: UInt16ArrayZeroPad): UInt16ArrayZeroPad {
		val out = UInt16ArrayZeroPad(l.size + r.size + 1)
		for (rn in 0 until r.size) {
			var carry = 0
			for (ln in 0 until l.size + 1) {
				val n = ln + rn
				val res = out[n] + (l[ln] * r[rn]) + carry
				out[n] = res and 0xFFFF
				carry = res ushr 16
			}
			if (carry != 0) error("carry expected to be zero at this point")
		}
		return out
	}

	class DivRemSmall(val div: UInt16ArrayZeroPad, val rem: Int)

	fun divRemSmall(value: UInt16ArrayZeroPad, r: Int): DivRemSmall {
		val length = value.size
		var rem = 0
		val qq = UInt16ArrayZeroPad(value.size)
		for (i in length - 1 downTo 0) {
			val dd = (rem shl 16) + value[i]
			val q = dd / r
			rem = dd - q * r
			qq[i] = q
		}
		return DivRemSmall(qq, rem)
	}

	fun compare(l: UInt16ArrayZeroPad, r: UInt16ArrayZeroPad): Int {
		for (n in max(l.size, r.size) - 1 downTo 0) {
			val vl = l[n]
			val vr = r[n]
			if (vl < vr) return -1
			if (vl > vr) return +1
		}
		return 0
	}
}
