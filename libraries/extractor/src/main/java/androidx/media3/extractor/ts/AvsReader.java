/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.extractor.ts;

import static com.google.common.base.Preconditions.checkNotNull;

import android.util.Pair;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.container.NalUnitUtil;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;
import java.util.Arrays;
import java.util.Collections;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Parses a continuous AVS1 (GB/T 20090.2) byte stream and extracts individual frames.
 *
 * <p>AVS1 与 MPEG-2 一样是「起始码 + 帧级独立编码」的结构，所以切帧思路与 {@link H262Reader}
 * 一致：每个 picture_start_code 开一个新样本。区别只在起始码取值与 sequence_header 的字段布局。
 *
 * <p>覆盖范围：
 *
 * <ul>
 *   <li>AVS1-P2（JiZhun / 基准档次，老的标清 AVS）
 *   <li>AVS1-P16（Guangdian / 广电 AVS+，直播落地用这个）
 * </ul>
 *
 * <p>P16 相对 P2 只多了若干扩展段（EXTENSION_START_CODE 承载），帧结构不变，因此同一套切帧逻辑
 * 对两者都成立。
 */
@UnstableApi
public final class AvsReader implements ElementaryStreamReader {

  private static final String TAG = "AvsReader";

  /** sequence_header()：宽高、帧率等全局参数。 */
  private static final int START_SEQUENCE_HEADER = 0xB0;
  /** sequence_end()：一段序列结束。 */
  private static final int START_SEQUENCE_END = 0xB1;
  /** user_data()：台标、时码一类。 */
  private static final int START_USER_DATA = 0xB2;
  /** I 帧。 */
  private static final int START_I_PICTURE = 0xB3;
  /** extension_start_code()（sequence display / copyright / picture 扩展）。 */
  private static final int START_EXTENSION = 0xB5;
  /** P 帧；AVS+ 里 PB 帧共用此起始码。 */
  private static final int START_P_PICTURE = 0xB6;
  /** B 帧。 */
  private static final int START_B_PICTURE = 0xB7;

  /**
   * AVS1 frame_rate_code（1~8）对应的帧率，为 AVS1 标准的 8 个取值。
   * code 9~15 在 AVS1 里保留/未定义，不按 FFmpeg 复用的 MPEG 非官方扩展取值。
   */
  private static final double[] AVS1_FRAME_RATE_VALUES =
      new double[] {24000d / 1001, 24, 25, 30000d / 1001, 30, 50, 60000d / 1001, 60};

  /**
   * AVS2 frame_rate_code（1~13）对应的帧率，与 FFmpeg {@code ff_avs2_frame_rate_tab} 一致；
   * code 0 / 14~15 为保留值。
   */
  private static final double[] AVS2_FRAME_RATE_VALUES =
      new double[] {
        24000d / 1001, 24, 25, 30000d / 1001, 30, 50, 60000d / 1001, 60, 100, 120, 200, 240, 300
      };

  private final String containerMimeType;
  private final String sampleMimeType;
  /**
   * 是否为 AVS2：两者宽高都是 14 位，但 AVS2 多 1bit field_coded_sequence（Main10 还多
   * encoding_precision），后续字段位置不同。
   */
  private final boolean isAvs2;
  private final boolean[] prefixFlags;
  private final CsdBuffer csdBuffer;

  private @MonotonicNonNull String formatId;
  private @MonotonicNonNull TrackOutput output;

  // 以下状态在 seek 时重置。
  private long totalBytesWritten;
  private boolean startedFirstSample;

  // 以下状态不随 seek 重置。
  private boolean hasOutputFormat;
  private long frameDurationUs;
  /** 已下发 Format 的宽高，用于判断解析出的真实尺寸是否需要更新。 */
  private int outputWidth;
  private int outputHeight;

  // 每个 PES 包开始时重置。
  private long pesTimeUs;
  private boolean randomAccessIndicator;

  // 每个样本开始时重置。
  private long samplePosition;
  private long sampleTimeUs;
  private boolean sampleIsKeyframe;
  private boolean sampleHasPicture;

  public AvsReader(String containerMimeType) {
    this(containerMimeType, MimeTypes.VIDEO_AVS);
  }

  /* package */ AvsReader(String containerMimeType, String sampleMimeType) {
    this.containerMimeType = containerMimeType;
    this.sampleMimeType = sampleMimeType;
    this.isAvs2 = MimeTypes.VIDEO_AVS2.equals(sampleMimeType);
    prefixFlags = new boolean[4];
    csdBuffer = new CsdBuffer(128);
    pesTimeUs = C.TIME_UNSET;
    sampleTimeUs = C.TIME_UNSET;
    outputWidth = Format.NO_VALUE;
    outputHeight = Format.NO_VALUE;
  }

  @Override
  public void seek() {
    NalUnitUtil.clearPrefixFlags(prefixFlags);
    csdBuffer.reset();
    totalBytesWritten = 0;
    startedFirstSample = false;
    randomAccessIndicator = false;
    pesTimeUs = C.TIME_UNSET;
    sampleTimeUs = C.TIME_UNSET;
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    formatId = idGenerator.getFormatId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_VIDEO);
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    this.pesTimeUs = pesTimeUs;
    randomAccessIndicator |= (flags & TsPayloadReader.FLAG_RANDOM_ACCESS_INDICATOR) != 0;
  }

  @Override
  public void consume(ParsableByteArray data) {
    // Asserts that createTracks has been called.
    checkNotNull(output);
    int offset = data.getPosition();
    int limit = data.limit();
    byte[] dataArray = data.getData();

    // 先把整段数据交给 TrackOutput，样本的字节序由此累积；下面只负责给出样本边界与时间戳。
    totalBytesWritten += data.bytesLeft();
    output.sampleData(data, data.bytesLeft());

    while (true) {
      int startCodeOffset = NalUnitUtil.findNalUnit(dataArray, offset, limit, prefixFlags);
      if (startCodeOffset == limit) {
        // 扫到末尾没有新的起始码：把剩余字节继续喂给 csd 缓冲。
        csdBuffer.onData(dataArray, offset, limit);
        return;
      }

      int startCodeValue = dataArray[startCodeOffset + 3] & 0xFF;
      // 从当前 offset 到下一个起始码的字节数；起始码跨缓冲区时为负。
      int lengthToStartCode = startCodeOffset - offset;
      boolean isPictureStartCode =
          startCodeValue == START_I_PICTURE
              || startCodeValue == START_P_PICTURE
              || startCodeValue == START_B_PICTURE;

      if (lengthToStartCode > 0) {
        csdBuffer.onData(dataArray, offset, startCodeOffset);
      }
      int bytesAlreadyPassed = lengthToStartCode < 0 ? -lengthToStartCode : 0;
      boolean csdComplete = csdBuffer.onStartCode(startCodeValue, bytesAlreadyPassed);
      // 直播流常从中间接入：首个 PES 往往已经是 picture，sequence_header 要等后面
      // 的随机接入点才出现。所以 csd 必须始终收集（不能只在首次出 Format 前收），
      // 拿到后若尺寸与已下发的兜底值不同，再补一次 Format 把真实宽高纠正过来。
      if (csdComplete || (!hasOutputFormat && isPictureStartCode)) {
        Pair<Format, Long> result =
            parseCsdBuffer(
                csdBuffer,
                checkNotNull(formatId),
                containerMimeType,
                sampleMimeType,
                isAvs2);
        Format parsedFormat = result.first;
        if (!hasOutputFormat
            || parsedFormat.width != outputWidth
            || parsedFormat.height != outputHeight) {
          output.format(parsedFormat);
          outputWidth = parsedFormat.width;
          outputHeight = parsedFormat.height;
          frameDurationUs = result.second;
          hasOutputFormat = true;
        }
      }

      if (isPictureStartCode) {
        int bytesWrittenPastStartCode = limit - startCodeOffset;
        if (sampleHasPicture && hasOutputFormat && sampleTimeUs != C.TIME_UNSET) {
          // 上一帧到这里结束，输出它的元数据。
          @C.BufferFlags int flags = sampleIsKeyframe ? C.BUFFER_FLAG_KEY_FRAME : 0;
          int size = (int) (totalBytesWritten - samplePosition) - bytesWrittenPastStartCode;
          output.sampleMetadata(sampleTimeUs, flags, size, bytesWrittenPastStartCode, null);
        }
        if (!startedFirstSample || sampleHasPicture) {
          samplePosition = totalBytesWritten - bytesWrittenPastStartCode;
          sampleTimeUs =
              pesTimeUs != C.TIME_UNSET
                  ? pesTimeUs
                  : (sampleTimeUs != C.TIME_UNSET
                      ? (sampleTimeUs + frameDurationUs)
                      : C.TIME_UNSET);
          // I 帧或 TS 头标了随机接入点，都算关键帧。
          sampleIsKeyframe = startCodeValue == START_I_PICTURE || randomAccessIndicator;
          randomAccessIndicator = false;
          pesTimeUs = C.TIME_UNSET;
          startedFirstSample = true;
        }
        sampleHasPicture = true;
      }

      offset = startCodeOffset + 3;
    }
  }

  @Override
  public void endOfInputReached() {
    // Asserts that createTracks has been called.
    checkNotNull(output);
    if (sampleHasPicture && sampleTimeUs != C.TIME_UNSET && hasOutputFormat) {
      @C.BufferFlags int flags = sampleIsKeyframe ? C.BUFFER_FLAG_KEY_FRAME : 0;
      int size = (int) (totalBytesWritten - samplePosition);
      output.sampleMetadata(sampleTimeUs, flags, size, /* offset= */ 0, /* cryptoData= */ null);
    }
  }

  /**
   * 解析 sequence_header 得到 Format 与帧时长。
   *
   * <p>两代标准的位布局不同，必须分开解析（{@code csdData} 均以 {@code 00 00 01 B0}
   * 开头，故 header 首字节在索引 4）：
   *
   * <p>AVS1（GB/T 20090.2）—— 宽高各 14 位（AVS+ P16 同）：
   *
   * <pre>
   * profile_id(8) level_id(8) progressive_sequence(1)
   * horizontal_size(14) vertical_size(14)
   * chroma_format(2) sample_precision(3) aspect_ratio(4) frame_rate_code(4) ...
   * </pre>
   *
   * <p>AVS2（GB/T 33475.2）—— 宽高各 14 位，且多一个 field_coded_sequence 位
   * （与 FFmpeg {@code avs2_parser.c} 的 parse_avs2_seq_header 一致）：
   *
   * <pre>
   * profile_id(8) level_id(8) progressive_sequence(1) field_coded_sequence(1)
   * horizontal_size(14) vertical_size(14)
   * chroma_format(2) sample_precision(3) [encoding_precision(3) 仅 Main10]
   * aspect_ratio(4) frame_rate_code(4) ...
   * </pre>
   */
  private static Pair<Format, Long> parseCsdBuffer(
      CsdBuffer csdBuffer,
      String formatId,
      String containerMimeType,
      String sampleMimeType,
      boolean isAvs2) {
    byte[] csdData = Arrays.copyOf(csdBuffer.data, csdBuffer.length);

    int width = Format.NO_VALUE;
    int height = Format.NO_VALUE;
    float frameRate = Format.NO_VALUE;
    long frameDurationUs = 0;
    float pixelWidthHeightRatio = 1f;

    // 至少 12 字节才够读到 frame_rate_code（AVS1 分支访问 index 11）。
    if (csdData.length >= 12) {
      int aspectRatioCode;
      int frameRateCode;
      if (isAvs2) {
        int profile = csdData[4] & 0xFF;
        int d2 = csdData[6] & 0xFF;
        int d3 = csdData[7] & 0xFF;
        int d4 = csdData[8] & 0xFF;
        int d5 = csdData[9] & 0xFF;
        int d6 = csdData[10] & 0xFF;
        int d7 = csdData[11] & 0xFF;

        // width 14 位 = d2 低 6 位 + d3 全部 8 位（位 18..31，正好对齐字节）。
        // height 14 位 = 位 32..45，即 d4 全部 8 位 + d5 高 6 位，**不对齐字节**。
        width = ((d2 & 0x3F) << 8) | d3;
        height = ((d4 << 6) | (d5 >> 2)) & 0x3FFF;

        // 位 46..47 chroma、48..50 sample_precision；
        // 只有 Main10(0x22) 才有 51..53 encoding_precision，其余 profile 没有，
        // 所以后面 aspect_ratio / frame_rate_code 的位置要平移 3 位。
        if (profile == 0x22) {
          aspectRatioCode = ((d6 & 0x03) << 2) | (d7 >> 6);
          frameRateCode = (d7 >> 2) & 0x0F;
        } else {
          aspectRatioCode = (d6 >> 1) & 0x0F;
          frameRateCode = ((d6 & 0x01) << 3) | (d7 >> 5);
        }
      } else {
        int b2 = csdData[6] & 0xFF;
        int b3 = csdData[7] & 0xFF;
        int b4 = csdData[8] & 0xFF;
        int b5 = csdData[9] & 0xFF;
        int b6 = csdData[10] & 0xFF;
        int b7 = csdData[11] & 0xFF;

        // AVS1/AVS+ 宽高各 14bit：b2 低 7 位 + b3 高 7 位；高度跨 b3/b4/b5。
        width = ((b2 & 0x7F) << 7) | (b3 >> 1);
        height = ((b3 & 0x01) << 13) | (b4 << 5) | (b5 >> 3);
        // chroma(2) + sample_precision(3) 占 b5 低 3 位 + b6 高 2 位，后面才是 aspect/frame_rate。
        aspectRatioCode = (b6 >> 2) & 0x0F;
        frameRateCode = ((b6 & 0x03) << 2) | (b7 >> 6);
      }

      switch (aspectRatioCode) {
        case 2:
          pixelWidthHeightRatio = (4 * height) / (float) (3 * width);
          break;
        case 3:
          pixelWidthHeightRatio = (16 * height) / (float) (9 * width);
          break;
        case 4:
          pixelWidthHeightRatio = (221 * height) / (float) (100 * width);
          break;
        default:
          // 1 = 方形像素，保持 1f。
          break;
      }

      double[] frameRateValues = isAvs2 ? AVS2_FRAME_RATE_VALUES : AVS1_FRAME_RATE_VALUES;
      int frameRateIndex = frameRateCode - 1;
      if (frameRateIndex >= 0 && frameRateIndex < frameRateValues.length) {
        double frameRateValue = frameRateValues[frameRateIndex];
        frameRate = (float) frameRateValue;
        frameDurationUs = (long) (C.MICROS_PER_SECOND / frameRateValue);
      }
    }

    // 尺寸合法性兜底：MediaCodec.configure 会用 width*height 算缓冲大小，
    // 畸形尺寸（例如 csd 不是真正的 sequence_header 时解析出的 65535）会在
    // libstagefright 里触发 ubsan mul-overflow 直接 SIGABRT。
    if (width <= 0
        || height <= 0
        || width > 8192
        || height > 8192
        || (long) width * height > 8192L * 4320L) {
      Log.w(TAG, "reject implausible dims " + width + "x" + height + ", fallback 1920x1080");
      width = 1920;
      height = 1080;
      pixelWidthHeightRatio = 1f;
    }

    Format format =
        new Format.Builder()
            .setId(formatId)
            .setContainerMimeType(containerMimeType)
            .setSampleMimeType(sampleMimeType)
            .setWidth(width)
            .setHeight(height)
            .setFrameRate(frameRate)
            .setPixelWidthHeightRatio(pixelWidthHeightRatio)
            .setMaxInputSize(4 * 1024 * 1024)
            .setInitializationData(
                csdData.length > 0 ? Collections.singletonList(csdData) : null)
            .build();

    return Pair.create(format, frameDurationUs);
  }

  /** 累积 sequence_header（及其后续 extension）作为编解码器初始化数据。 */
  private static final class CsdBuffer {

    private static final byte[] START_CODE = new byte[] {0, 0, 1};

    private boolean isFilling;

    public int length;
    public byte[] data;

    public CsdBuffer(int initialCapacity) {
      data = new byte[initialCapacity];
    }

    /** 重置缓冲。 */
    public void reset() {
      isFilling = false;
      length = 0;
    }

    /**
     * 处理一个起始码。
     *
     * @param startCodeValue 起始码值。
     * @param bytesAlreadyPassed 已经计入 csd 缓冲的、属于该起始码的字节数（通常为 0）。
     * @return csd 是否已收集完整。true 之后必须 {@link #reset()} 才能再次填充。
     */
    public boolean onStartCode(int startCodeValue, int bytesAlreadyPassed) {
      if (isFilling) {
        length -= bytesAlreadyPassed;
        // extension 属于 sequence header 的延伸（display / copyright），继续累积；
        // 其他起始码（picture / sequence_end / user_data）表示 csd 到此为止。
        if (startCodeValue != START_EXTENSION) {
          isFilling = false;
          return true;
        }
      } else if (startCodeValue == START_SEQUENCE_HEADER) {
        // 直播流里 sequence_header 会周期性重复出现，这里必须清空旧内容再收，
        // 否则第二次及以后收集到的 csd 会拼在上一段后面。
        length = 0;
        isFilling = true;
      }
      onData(START_CODE, 0, START_CODE.length);
      return false;
    }

    /** 累积码流数据。 */
    public void onData(byte[] newData, int offset, int limit) {
      if (!isFilling) {
        return;
      }
      int readLength = limit - offset;
      if (data.length < length + readLength) {
        data = Arrays.copyOf(data, (length + readLength) * 2);
      }
      System.arraycopy(newData, offset, data, length, readLength);
      length += readLength;
    }
  }
}
