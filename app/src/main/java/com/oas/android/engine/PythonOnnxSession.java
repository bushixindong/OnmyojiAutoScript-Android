package com.oas.android.engine;

import ai.onnxruntime.*;
import android.content.res.AssetFileDescriptor;
import android.util.Base64;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import org.json.*;
import java.io.FileInputStream;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.util.*;

/** Real ONNX inference for the original Python models, backed by Android's native ORT. */
public final class PythonOnnxSession implements AutoCloseable {
    private final OrtEnvironment environment=OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private ByteBuffer floatInput;
    public PythonOnnxSession(String path)throws Exception{
        try(OrtSession.SessionOptions options=options()){session=environment.createSession(path,options);}
    }
    public PythonOnnxSession(byte[] model)throws Exception{
        try(OrtSession.SessionOptions options=options()){session=environment.createSession(model,options);}
    }
    private PythonOnnxSession(ByteBuffer model) throws OrtException {
        try (OrtSession.SessionOptions options = options()) {
            session = environment.createSession(model, options);
        }
    }
    public static PythonOnnxSession openAsset(String path) throws Exception {
        AndroidPlatform platform = (AndroidPlatform) Python.getPlatform();
        try (AssetFileDescriptor asset = platform.getApplication().getAssets().openFd(path);
             FileInputStream input = asset.createInputStream()) {
            ByteBuffer model = input.getChannel().map(FileChannel.MapMode.READ_ONLY, asset.getStartOffset(), asset.getLength());
            return new PythonOnnxSession(model);
        }
    }
    private static OrtSession.SessionOptions options()throws OrtException{
        OrtSession.SessionOptions result=new OrtSession.SessionOptions();
        result.setIntraOpNumThreads(Math.min(4,Math.max(1,Runtime.getRuntime().availableProcessors())));
        return result;
    }
    public String metadata()throws Exception{
        return new JSONObject().put("inputs",describe(session.getInputInfo())).put("outputs",describe(session.getOutputInfo())).toString();
    }
    private static JSONArray describe(Map<String,NodeInfo> nodes)throws Exception{
        JSONArray result=new JSONArray();
        for(Map.Entry<String,NodeInfo> entry:nodes.entrySet()){
            TensorInfo info=(TensorInfo)entry.getValue().getInfo();JSONArray shape=new JSONArray();
            for(long dim:info.getShape())shape.put(dim<0?JSONObject.NULL:dim);
            String pyType=dtype(info.type);if(pyType.equals("float32"))pyType="float";else if(pyType.equals("float64"))pyType="double";
            result.put(new JSONObject().put("name",entry.getKey()).put("shape",shape).put("type","tensor("+pyType+")"));
        }
        return result;
    }
    public synchronized byte[] runYolo(String inputName, byte[] raw, long[] shape, float threshold) throws OrtException {
        if (floatInput == null || floatInput.capacity() != raw.length) {
            floatInput = ByteBuffer.allocateDirect(raw.length).order(ByteOrder.LITTLE_ENDIAN);
        }
        floatInput.clear();
        floatInput.put(raw).flip();
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, floatInput.asFloatBuffer(), shape);
             OrtSession.Result result = session.run(Collections.singletonMap(inputName, tensor))) {
            OnnxTensor detections = (OnnxTensor) result.get(0);
            long[] dimensions = detections.getInfo().getShape();
            if (dimensions.length != 3 || dimensions[0] != 1 || dimensions[1] <= 4) {
                throw new IllegalStateException("需要 YOLO [1,4+类别数,候选框数] 输出");
            }
            return decodeYolo(detections.getFloatBuffer(), (int) dimensions[1], (int) dimensions[2], threshold);
        }
    }

    static byte[] decodeYolo(FloatBuffer values, int channels, int anchors, float threshold) {
        float[] best = new float[anchors];
        int[] labels = new int[anchors];
        Arrays.fill(best, threshold);
        Arrays.fill(labels, -1);
        for (int channel = 4; channel < channels; channel++) {
            int offset = channel * anchors;
            for (int anchor = 0; anchor < anchors; anchor++) {
                float score = values.get(offset + anchor);
                if (score > best[anchor]) {
                    best[anchor] = score;
                    labels[anchor] = channel - 4;
                }
            }
        }
        int count = 0;
        for (int label : labels) if (label >= 0) count++;
        ByteBuffer output = ByteBuffer.allocate(count * 6 * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int anchor = 0; anchor < anchors; anchor++) {
            if (labels[anchor] < 0) continue;
            for (int field = 0; field < 4; field++) output.putFloat(values.get(field * anchors + anchor));
            output.putFloat(best[anchor]).putFloat(labels[anchor]);
        }
        return output.array();
    }
    public synchronized String run(String request)throws Exception{
        JSONObject data=new JSONObject(request),response=new JSONObject();Map<String,OnnxTensor> inputs=new LinkedHashMap<>();
        try{
            Iterator<String> keys=data.keys();
            while(keys.hasNext()){
                String name=keys.next();JSONObject input=data.getJSONObject(name);JSONArray dimensions=input.getJSONArray("shape");
                long[] shape=new long[dimensions.length()];for(int i=0;i<shape.length;i++)shape[i]=dimensions.getLong(i);
                byte[] raw=Base64.decode(input.getString("data"),Base64.DEFAULT);ByteBuffer bytes=ByteBuffer.allocateDirect(raw.length).order(ByteOrder.LITTLE_ENDIAN);bytes.put(raw).rewind();
                OnnxTensor tensor;
                switch(input.getString("dtype")){
                    case "float32":tensor=OnnxTensor.createTensor(environment,bytes.asFloatBuffer(),shape);break;
                    case "float64":tensor=OnnxTensor.createTensor(environment,bytes.asDoubleBuffer(),shape);break;
                    case "int64":tensor=OnnxTensor.createTensor(environment,bytes.asLongBuffer(),shape);break;
                    case "int32":tensor=OnnxTensor.createTensor(environment,bytes.asIntBuffer(),shape);break;
                    case "uint8":tensor=OnnxTensor.createTensor(environment,bytes,shape,OnnxJavaType.UINT8);break;
                    case "int8":tensor=OnnxTensor.createTensor(environment,bytes,shape,OnnxJavaType.INT8);break;
                    case "bool":tensor=OnnxTensor.createTensor(environment,bytes,shape,OnnxJavaType.BOOL);break;
                    default:throw new IllegalArgumentException("Unsupported ONNX dtype: "+input.getString("dtype"));
                }
                inputs.put(name,tensor);
            }
            try(OrtSession.Result result=session.run(inputs)){
                for(String name:session.getOutputNames()){
                    OnnxTensor tensor=(OnnxTensor)result.get(name).orElseThrow(()->new IllegalStateException("Missing model output"));
                    TensorInfo info=tensor.getInfo();String type=dtype(info.type);int size=info.type==OnnxJavaType.DOUBLE||info.type==OnnxJavaType.INT64?8:info.type==OnnxJavaType.FLOAT||info.type==OnnxJavaType.INT32?4:1;
                    ByteBuffer bytes=ByteBuffer.allocate(Math.toIntExact(info.getNumElements()*size)).order(ByteOrder.LITTLE_ENDIAN);
                    flatten(tensor.getValue(),bytes,info.type);JSONArray shape=new JSONArray();for(long dim:info.getShape())shape.put(dim);
                    response.put(name,new JSONObject().put("dtype",type).put("shape",shape).put("data",Base64.encodeToString(bytes.array(),Base64.NO_WRAP)));
                }
            }
            return response.toString();
        }finally{for(OnnxTensor input:inputs.values())input.close();}
    }
    private static void flatten(Object value,ByteBuffer output,OnnxJavaType type){
        if(value.getClass().isArray()){for(int i=0;i<java.lang.reflect.Array.getLength(value);i++)flatten(java.lang.reflect.Array.get(value,i),output,type);return;}
        switch(type){
            case FLOAT:output.putFloat(((Number)value).floatValue());break;
            case DOUBLE:output.putDouble(((Number)value).doubleValue());break;
            case INT64:output.putLong(((Number)value).longValue());break;
            case INT32:output.putInt(((Number)value).intValue());break;
            case BOOL:output.put((byte)((Boolean)value?1:0));break;
            default:output.put(((Number)value).byteValue());
        }
    }
    private static String dtype(OnnxJavaType type){
        switch(type){case FLOAT:return "float32";case DOUBLE:return "float64";case INT64:return "int64";case INT32:return "int32";case BOOL:return "bool";case UINT8:return "uint8";case INT8:return "int8";default:throw new IllegalArgumentException("Unsupported tensor type: "+type);}
    }
    @Override public void close()throws OrtException{session.close();}
}
