package cc.huevia.ncp;

import java.util.Arrays;
import java.util.Locale;

/** Immutable manual settings. Exposure is stored in tenths of an EV; all other values are integers. */
public final class PhotoAdjustments {
    public static final String MODEL = "photo-manual-linear-wb-tones-v1";
    public enum Parameter {
        BRIGHTNESS("亮度", -100, 100), EXPOSURE("曝光", -30, 30), CONTRAST("对比度", -100, 100),
        HIGHLIGHTS("高光", -100, 100), SHADOWS("阴影", -100, 100), WHITES("白色", -100, 100),
        BLACKS("黑色", -100, 100), TEMPERATURE("色温", -100, 100), TINT("色调", -100, 100),
        SATURATION("饱和度", -100, 100), VIBRANCE("自然饱和度", -100, 100),
        CLARITY("清晰度", -100, 100), SHARPENING("锐化", 0, 100);
        public final String label;
        public final int min, max;
        Parameter(String label, int min, int max) { this.label=label;this.min=min;this.max=max; }
        public String format(int value) {
            if(this==EXPOSURE)return String.format(Locale.ROOT,"%+.1f EV",value/10.0);
            return value>0?"+"+value:String.valueOf(value);
        }
        public String help(){
            switch(this){
                case EXPOSURE:return "整体增减照片明暗，适合修正欠曝或偏亮；与主要作用于中间亮度的“亮度”不同。这是 JPEG 后期等效曝光补偿，无法找回已经变成纯白的细节。";
                case BRIGHTNESS:return "主要调整中间亮度，让主体更亮或更暗，尽量保留明暗两端。需要整体提亮时可先试曝光，再用亮度细调。";
                case CONTRAST:return "正值增加明暗反差，负值使画面柔和。反差过大时，亮部和暗部的层次可能减少。";
                case HIGHLIGHTS:return "调整较亮区域；负值压低亮部，正值提亮。高光作用范围比“白色”宽，不能恢复 JPEG 已经丢失的纯白细节。";
                case SHADOWS:return "调整较暗区域；正值提亮暗部，负值加深。阴影作用范围比“黑色”宽，提亮也可能显露原有噪点。";
                case WHITES:return "调整照片最亮端的白点与层次，比“高光”更集中于接近白色的区域。负值收住最亮部分，正值增加白色。";
                case BLACKS:return "调整最暗端的黑位，比“阴影”更集中于接近黑色的区域。正值抬黑，负值加深黑位。";
                case TEMPERATURE:return "负值偏冷、偏蓝，正值偏暖、偏黄。这里是 JPEG 的相对冷暖调整，不是拍摄时的绝对开尔文色温。";
                case TINT:return "修正冷暖之外的偏色：负值偏绿，正值偏洋红。可先调色温，再少量调整色调。";
                case SATURATION:return "整体降低或增加色彩浓度，−100 变为灰度。与自然饱和度相比，整体影响更直接。";
                case VIBRANCE:return "正向优先增强较淡的颜色，减少继续增强已经浓艳的颜色；负向降低色彩鲜艳程度。";
                case CLARITY:return "调整局部反差与纹理。正值更有质感，负值更柔和；过高可能让皮肤纹理和噪点更明显。";
                case SHARPENING:return "增强边缘的清楚程度。0 表示不额外锐化，不会取消预设本身的锐化；可放大照片检查边缘是否自然。";
                default:throw new AssertionError(this);
            }
        }
    }
    public static final PhotoAdjustments NONE = new PhotoAdjustments(new int[Parameter.values().length]);
    private final int[] values;
    public PhotoAdjustments(int[] values) {
        if(values==null||values.length!=Parameter.values().length)throw new IllegalArgumentException("基础调整数据不完整。");
        this.values=values.clone();
        for(Parameter p:Parameter.values())if(get(p)<p.min||get(p)>p.max)throw new IllegalArgumentException(p.label+"数值超出范围。");
    }
    public int get(Parameter p){return values[p.ordinal()];}
    public int[] values(){return values.clone();}
    public PhotoAdjustments with(Parameter p,int value){int[] next=values();next[p.ordinal()]=value;return new PhotoAdjustments(next);}
    public int count(){int count=0;for(int value:values)if(value!=0)count++;return count;}
    public boolean isNeutral(){return count()==0;}
    public String identity(){StringBuilder s=new StringBuilder(MODEL);for(int value:values)s.append(':').append(value);return s.toString();}
    @Override public boolean equals(Object other){return other instanceof PhotoAdjustments&&Arrays.equals(values,((PhotoAdjustments)other).values);}
    @Override public int hashCode(){return Arrays.hashCode(values);}
}
