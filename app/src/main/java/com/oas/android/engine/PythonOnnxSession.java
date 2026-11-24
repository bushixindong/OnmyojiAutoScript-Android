package com.oas.android.engine;

import ai.onnxruntime.*;
import android.util.Base64;
import org.json.*;
import java.nio.*;
import java.util.*;

/** Real ONNX inference for the original Python models, backed by Android's native ORT. */
public final class PythonOnnxSession implements AutoCloseable {
    private final OrtEnvironment environment=OrtEnvironment.getEnvironment();
    private final OrtSession session;
    public PythonOnnxSession(String path)throws Exception{
        try(OrtSession.SessionOptions options=options()){session=environment.createSession(path,options);}
    }
    public PythonOnnxSession(byte[] model)throws Exception{
        try(OrtSession.SessionOptions options=options()){session=environment.createSession(model,options);}
    }
    private static OrtSession.SessionOptions options()throws OrtException{
        OrtSession.SessionOptions result=new OrtSession.SessionOptions();result.setIntraOpNumThreads(2);return result;
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
