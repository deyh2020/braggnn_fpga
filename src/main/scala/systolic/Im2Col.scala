package systolic

import chisel3._
import chisel3.util._

import scala.collection.mutable.ArrayBuffer

class Im2Col[T <: Bits](
    val dtype: T,
    val imgSize: Int,
    val inChannels: Int, // num filters or channels in
    val kernelSize: Int,
    qSize: Int,
    val stride: Int,
    val padding: Boolean,
    val throughput: Double,
    val inputCycles: Int,
    val noFifo: Boolean = false,
    debug: Boolean = false
) extends Module {

  val dWidth   = dtype.getWidth
  val outWidth = (dWidth * throughput).toInt
  val outUInt  = UInt(width = outWidth.W)
  val cycles   = (dtype.getWidth / outWidth) // TODO(max) output cycles?
  val cyclesBW = {
    if (log2Ceil(cycles) == 0)
      1
    else
      log2Ceil(cycles)
  }

  val io = IO(new Bundle {
    val dataIn  = Flipped(Valid(Vec(inChannels, dtype.cloneType)))
    val dataOut = Valid(Vec(kernelSize * kernelSize * inChannels, outUInt.cloneType))
  })
  // TODO(max) 3 x 16 concatenated into a UInt48
  val dataInAsUInt = io.dataIn.bits.asInstanceOf[Vec[Bits]].map(_.asUInt()).reduce(_ ## _)
  val queueIOIn    = Wire(Decoupled(dataInAsUInt.cloneType))
  queueIOIn.bits := dataInAsUInt
  queueIOIn.valid := io.dataIn.valid

  val uintBits = Wire(dataInAsUInt.cloneType)
  val vldIn    = Wire(Bool())
  val rdy      = Wire(Bool())
  // TODO(max) queue should be outside?
  if (noFifo) {
    uintBits := RegEnable(dataInAsUInt, rdy)
    vldIn := RegEnable(io.dataIn.valid, false.B, rdy)
    queueIOIn.ready := rdy
  } else {
    val q = Queue(queueIOIn, qSize)
    uintBits := q.bits
    vldIn := q.valid
    q.ready := rdy
  }

  val cntr = {
    if (cycles > 1) {
      val r = RegInit(0.U(cyclesBW.W))
      when(vldIn) {
        r := r + 1.U(1.W)
      }
      r
    } else
      0.U(1.W)
  }
  rdy := !vldIn // TODO(max): ready when queue input is no longer valid? also this wire is reused? why?
  when(cntr === (cycles - 1).U(cyclesBW.W)) {
    rdy := true.B
  }
  val running = RegInit(false.B)
  when(!running && io.dataIn.valid) {
    running := true.B
  }
  val padAmt = {
    if (padding)
      (kernelSize / 2).toInt // TODO(max) this is floor - it should be plus 1?
    else
      0
  }
  val cyclesPerImage = imgSize * imgSize * cycles * inputCycles
  val inCntr         = Counter(vldIn, cyclesPerImage)

  val rowCntr = Reg(UInt(width = log2Ceil(imgSize).W))
  val colCntr = Reg(UInt(width = log2Ceil(imgSize).W))
  rowCntr := inCntr._1(
    log2Ceil(imgSize * cycles * inputCycles) - 1,
    log2Ceil(cycles * inputCycles)
  ) // TODO(max) wtf does this mean? slice the register or value?
  "Returns a subset of bits on this Bits from hi to lo (inclusive), statically addressed.\nParams:\nx – the high bit\ny – the low bit"
  colCntr := inCntr._1(log2Ceil(cyclesPerImage) - 1, log2Ceil(imgSize * cycles * inputCycles))

  if (padding) {
    val cyclesPerImagePad = imgSize * padAmt * cycles * inputCycles
    val padStart          = RegInit(false.B)
    when(running & inCntr._2) { // when wrap
      padStart := true.B
    }
    val padCntr = Counter(padStart, cyclesPerImagePad)
    when(padCntr._2) {
      padStart := false.B
    }
    val padDelay = RegNext(padStart)
    when(padStart | padDelay) {
      rowCntr := padCntr._1(
        log2Ceil(imgSize * cycles * inputCycles) - 1,
        log2Ceil(cycles * inputCycles)
      )
    }
  }

  val bitsOut    = Reg(Vec(inChannels, outUInt.cloneType))
  val dtypeWidth = dtype.getWidth // this is the actual transformation?
  for (i <- 0 until inChannels) {
    bitsOut(inChannels - i - 1) := uintBits(
      (i + 1) * dtypeWidth - 1 - outWidth * (cycles - 1),
      i * dtypeWidth
    )
    for (j <- 1 until cycles) {
      when(cntr === j.U) {
        bitsOut(inChannels - i - 1) := uintBits(
          (i + 1) * dtypeWidth - 1 - outWidth * (cycles - 1 - j),
          i * dtypeWidth + outWidth * j
        )
      }
    }
  }

  val noReg      = (kernelSize - 1) * (cycles * inputCycles)
  val bufferSize = cycles * inputCycles * imgSize - noReg

  val outputs = ArrayBuffer[Vec[UInt]]()

  outputs.append(bitsOut)
  // TODO(max) hook up stacks of registers to bitsOut?
  for (i <- 0 until kernelSize) {
    var currVec = outputs.last
    for (j <- 1 until kernelSize) {
      for (c <- 0 until cycles * inputCycles)
        currVec = RegNext(currVec)
      outputs.append(currVec)
    }
    if (i < kernelSize - 1) {
      val mb = MemShiftRegister(currVec, bufferSize, true.B)
      outputs.append(mb)
    }
  }

  val padded = {
    if (padding) {
      val padOut = outputs.toList.map(_.toList)
      val rOut   = Reg(VecInit(padOut.reduce(_ ++ _)).cloneType)
      for (y <- 0 until kernelSize) {
        for (x <- 0 until kernelSize) {
          val grp = padOut(y * kernelSize + x)
          for (g <- grp.zipWithIndex) {
            rOut((y * kernelSize + x) * inChannels + g._2) := g._1
            if (x < padAmt) {
              val rightCond = ShiftRegister(rowCntr <= (padAmt - x - 1).U, 0)
              when(rightCond) {
                rOut((y * kernelSize + x) * inChannels + g._2) := 0.U
              }
            }
            if (x > padAmt) {
              val leftCond = ShiftRegister(rowCntr >= padAmt.U && rowCntr <= (x - padAmt).U, 0)
              when(leftCond) {
                rOut((y * kernelSize + x) * inChannels + g._2) := 0.U
              }
            }
            if (y < padAmt) {
              val botCond = ShiftRegister(colCntr <= (padAmt - y - 1).U, cycles)
              when(botCond) {
                rOut((y * kernelSize + x) * inChannels + g._2) := 0.U
              }
            }
            if (y > padAmt) {
              val topCond = ShiftRegister(colCntr >= padAmt.U && colCntr <= (y - padAmt).U, cycles)
              when(topCond) {
                rOut((y * kernelSize + x) * inChannels + g._2) := 0.U
              }
            }
          }
        }
      }
      rOut
    } else
      RegNext(VecInit(outputs.toList.map(_.toList).reduce(_ ++ _)))
  }

  io.dataOut.bits := padded

  val latency = {
    if (padding)
      inputCycles * cycles * imgSize * (kernelSize - 2) + (kernelSize - 2) * cycles * inputCycles + 2
    else
      inputCycles * cycles * imgSize * (kernelSize - 1) + (kernelSize - 1) * cycles * inputCycles + 1
  }
  val vld = {
    if (stride == 2 && !padding) {
      val vldCycPerRow = (imgSize / stride).toInt
      val rowDelay     = !rowCntr(0) && ShiftRegister(vldIn, 1, false.B, true.B)
      ShiftRegister(!colCntr(0) && rowDelay, latency, false.B, true.B)
    } else
      ShiftRegister(vldIn, latency, false.B, true.B)
  }
  io.dataOut.valid := vld
}
