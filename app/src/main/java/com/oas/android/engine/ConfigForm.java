package com.oas.android.engine;

import android.content.Context;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Native typed editor for the original complete task schemas, including nested lists and maps. */
public final class ConfigForm {
    private interface Value {Object read()throws Exception;}
    private final Context context;
    private final JSONObject root,labels;
    private final Value value;
    public ConfigForm(Context context,LinearLayout container,JSONObject schema,JSONObject data,JSONObject labels)throws Exception{
        this.context=context;this.root=schema;this.labels=labels;this.value=build(container,schema,data,"",0);
    }
    public JSONObject read()throws Exception{return (JSONObject)value.read();}
    private JSONObject resolve(JSONObject schema)throws Exception{
        if(schema.has("$ref")){
            JSONObject result=root;
            for(String part:schema.getString("$ref").substring(2).split("/"))result=result.getJSONObject(part.replace("~1","/").replace("~0","~"));
            return result;
        }
        JSONArray choices=schema.optJSONArray("anyOf");
        if(choices!=null)for(int i=0;i<choices.length();i++)if(!choices.getJSONObject(i).optString("type").equals("null"))return resolve(choices.getJSONObject(i));
        return schema;
    }
    private JSONObject resolveForData(JSONObject schema,Object data)throws Exception{
        JSONArray choices=schema.optJSONArray("anyOf");if(choices==null)choices=schema.optJSONArray("oneOf");
        if(choices!=null){
            for(int i=0;i<choices.length();i++){
                JSONObject candidate=resolve(choices.getJSONObject(i));String type=candidate.optString("type");
                if(data instanceof JSONObject&&type.equals("object")){
                    JSONObject properties=candidate.optJSONObject("properties");boolean fits=true;
                    if(properties!=null){Iterator<String> names=properties.keys();while(names.hasNext()){String name=names.next();JSONObject property=properties.getJSONObject(name);if(property.has("const")&&!String.valueOf(property.get("const")).equals(String.valueOf(((JSONObject)data).opt(name))))fits=false;}}
                    if(fits)return candidate;
                }
                if(data instanceof String&&type.equals("string")||data instanceof Boolean&&type.equals("boolean")||data instanceof Number&&(type.equals("number")||type.equals("integer"))||data instanceof JSONArray&&type.equals("array"))return candidate;
            }
        }
        return resolve(schema);
    }
    private String label(String key){return labels.optString(key,key);}
    private void text(LinearLayout parent,String text){TextView view=new TextView(context);view.setText(text);view.setTextSize(14);view.setPadding(8,10,8,4);parent.addView(view);}
    private LinearLayout column(LinearLayout parent){LinearLayout result=new LinearLayout(context);result.setOrientation(LinearLayout.VERTICAL);result.setPadding(12,0,0,6);parent.addView(result);return result;}
    private Value build(LinearLayout parent,JSONObject original,Object data,String name,int depth)throws Exception{
        JSONArray variants=original.optJSONArray("oneOf");
        if(variants!=null&&variants.length()>1){
            JSONArray choices=variants;String[] titles=new String[choices.length()];int initial=0;JSONObject matched=resolveForData(original,data);
            for(int i=0;i<titles.length;i++){JSONObject choice=resolve(choices.getJSONObject(i));titles[i]=choice.optString("title","选项 "+(i+1));if(choice==matched)initial=i;}
            Spinner selector=new Spinner(context);selector.setAdapter(new ArrayAdapter<>(context,android.R.layout.simple_spinner_dropdown_item,titles));selector.setSelection(initial);parent.addView(selector);
            LinearLayout body=column(parent);Value[] selected=new Value[1];int[] index={initial};
            selected[0]=build(body,choices.getJSONObject(initial),data,name,depth+1);
            selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
                @Override public void onNothingSelected(AdapterView<?> view){}
                @Override public void onItemSelected(AdapterView<?> view,View row,int position,long id){if(position==index[0])return;try{body.removeAllViews();selected[0]=build(body,choices.getJSONObject(position),new JSONObject(),name,depth+1);index[0]=position;com.oas.android.ui.NativeFormStyle.apply(body,com.oas.android.ui.NativeFormStyle.isDark(context));}catch(Exception error){Toast.makeText(context,error.toString(),Toast.LENGTH_LONG).show();}}
            });
            return ()->selected[0].read();
        }
        JSONObject schema=resolveForData(original,data);String inferred=data instanceof JSONObject?"object":data instanceof JSONArray?"array":data instanceof Boolean?"boolean":data instanceof Integer||data instanceof Long?"integer":data instanceof Number?"number":"string";
        String type=schema.optString("type",inferred);
        if(schema.has("const")){text(parent,label(String.valueOf(schema.get("const"))));return ()->schema.get("const");}
        boolean nullable=false;JSONArray alternatives=original.optJSONArray("anyOf");
        if(alternatives!=null)for(int i=0;i<alternatives.length();i++)nullable|=alternatives.getJSONObject(i).optString("type").equals("null");
        if(nullable){
            CheckBox unset=new CheckBox(context);unset.setText(label(name)+" · 不设置");unset.setChecked(data==JSONObject.NULL);parent.addView(unset);
            LinearLayout body=column(parent);Value child=build(body,schema,data==JSONObject.NULL?defaultValue(schema):data,name,depth+1);
            body.setVisibility(unset.isChecked()?View.GONE:View.VISIBLE);unset.setOnCheckedChangeListener((view,checked)->body.setVisibility(checked?View.GONE:View.VISIBLE));
            return ()->unset.isChecked()?JSONObject.NULL:child.read();
        }
        if(type.equals("object")){
            LinearLayout body=parent;
            if(!name.isEmpty()){
                Button header=new Button(context);header.setText(label(name)+" ▸");parent.addView(header);body=column(parent);body.setVisibility(View.GONE);
                LinearLayout target=body;header.setOnClickListener(view->{boolean expand=target.getVisibility()==View.GONE;target.setVisibility(expand?View.VISIBLE:View.GONE);header.setText(label(name)+(expand?" ▾":" ▸"));});
            }
            JSONObject current=data instanceof JSONObject?(JSONObject)data:new JSONObject(),properties=schema.optJSONObject("properties");
            Map<String,Value> children=new LinkedHashMap<>();
            if(properties!=null){
                Iterator<String> keys=properties.keys();
                while(keys.hasNext()){
                    String key=keys.next();JSONObject child=properties.getJSONObject(key);
                    children.put(key,build(body,child,current.has(key)?current.get(key):defaultValue(child),key,depth+1));
                }
                return ()->{JSONObject result=new JSONObject();for(Map.Entry<String,Value> entry:children.entrySet())result.put(entry.getKey(),entry.getValue().read());return result;};
            }
            JSONObject itemSchema=schema.optJSONObject("additionalProperties");if(itemSchema==null)itemSchema=new JSONObject();
            List<Dynamic> entries=new ArrayList<>();Iterator<String> keys=current.keys();
            while(keys.hasNext()){String key=keys.next();addDynamic(body,entries,itemSchema,key,current.get(key),depth);}
            Button add=new Button(context);add.setText("添加条目");body.addView(add);LinearLayout target=body;JSONObject childSchema=itemSchema;
            add.setOnClickListener(view->{try{addDynamic(target,entries,childSchema,"",defaultValue(childSchema),depth);}catch(Exception error){Toast.makeText(context,error.getMessage(),Toast.LENGTH_LONG).show();}});
            return ()->{JSONObject result=new JSONObject();for(Dynamic entry:entries){String key=entry.key.getText().toString().trim();if(key.isEmpty()||result.has(key))throw new IllegalArgumentException("条目键名不能为空或重复");result.put(key,entry.value.read());}return result;};
        }
        if(!name.isEmpty())text(parent,label(name));
        String description=schema.optString("description");
        if(!description.isEmpty()&&labels.has(description)&&!label(description).trim().isEmpty()){TextView help=new TextView(context);help.setText(label(description));help.setTextSize(11);help.setPadding(8,0,8,6);parent.addView(help);}
        if(type.equals("array")){
            LinearLayout body=column(parent);List<Value> values=new ArrayList<>();JSONObject items=schema.optJSONObject("items");
            if(items==null&&schema.has("prefixItems"))items=schema.getJSONArray("prefixItems").optJSONObject(0);
            if(items==null)items=new JSONObject();JSONObject itemSchema=items;
            JSONArray current=data instanceof JSONArray?(JSONArray)data:new JSONArray();
            for(int i=0;i<current.length();i++)addRow(body,values,itemSchema,current.get(i),depth);
            Button add=new Button(context);add.setText("添加一项");body.addView(add);
            add.setOnClickListener(view->{try{addRow(body,values,itemSchema,defaultValue(itemSchema),depth);}catch(Exception error){Toast.makeText(context,error.getMessage(),Toast.LENGTH_LONG).show();}});
            return ()->{JSONArray result=new JSONArray();for(Value value:values)result.put(value.read());return result;};
        }
        JSONArray options=schema.optJSONArray("enum");
        if(options!=null){
            String[] names=new String[options.length()];int selected=0;
            for(int i=0;i<names.length;i++){Object option=options.get(i);names[i]=label(String.valueOf(option));if(String.valueOf(option).equals(String.valueOf(data)))selected=i;}
            Spinner choice=new Spinner(context);choice.setAdapter(new ArrayAdapter<>(context,android.R.layout.simple_spinner_dropdown_item,names));choice.setSelection(selected);parent.addView(choice);
            return ()->options.get(choice.getSelectedItemPosition());
        }
        if(type.equals("boolean")){
            CheckBox option=new CheckBox(context);option.setText("开启");option.setChecked(Boolean.TRUE.equals(data));parent.addView(option);return option::isChecked;
        }
        EditText input=new EditText(context);input.setText(data==null||data==JSONObject.NULL?"":String.valueOf(data));
        if(type.equals("integer"))input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        else if(type.equals("number"))input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        else if(name.toLowerCase(Locale.ROOT).matches(".*(password|token|secret).*"))input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        else if(type.equals("multi_line")){input.setMinLines(3);input.setGravity(android.view.Gravity.TOP);}
        else input.setSingleLine();
        parent.addView(input);
        if(type.equals("integer"))return ()->Long.parseLong(input.getText().toString().trim());
        if(type.equals("number"))return ()->Double.parseDouble(input.getText().toString().trim());
        return ()->input.getText().toString();
    }
    private static final class Dynamic{EditText key;Value value;}
    private void addDynamic(LinearLayout parent,List<Dynamic> entries,JSONObject schema,String name,Object data,int depth)throws Exception{
        LinearLayout row=column(parent);Dynamic entry=new Dynamic();entry.key=new EditText(context);entry.key.setHint("键名");entry.key.setText(name);row.addView(entry.key);
        entry.value=build(row,schema,data,"",depth+1);entries.add(entry);Button remove=new Button(context);remove.setText("删除条目");row.addView(remove);remove.setOnClickListener(view->{entries.remove(entry);parent.removeView(row);});
        com.oas.android.ui.NativeFormStyle.apply(row,com.oas.android.ui.NativeFormStyle.isDark(context));
    }
    private void addRow(LinearLayout parent,List<Value> values,JSONObject schema,Object data,int depth)throws Exception{
        LinearLayout row=column(parent);Value value=build(row,schema,data,"",depth+1);values.add(value);
        Button remove=new Button(context);remove.setText("删除这一项");row.addView(remove);remove.setOnClickListener(view->{values.remove(value);parent.removeView(row);});
        com.oas.android.ui.NativeFormStyle.apply(row,com.oas.android.ui.NativeFormStyle.isDark(context));
    }
    private Object defaultValue(JSONObject schema)throws Exception{
        if(schema.optBoolean("x-random-id"))return java.util.UUID.randomUUID().toString().replace("-","");
        if(schema.has("default"))return schema.get("default");JSONObject resolved=resolve(schema);
        if(resolved.has("default"))return resolved.get("default");
        switch(resolved.optString("type")){case "object":return new JSONObject();case "array":return new JSONArray();case "boolean":return false;case "integer":return 0;case "number":return 0.0;default:return "";}
    }
}
