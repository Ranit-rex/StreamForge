package app.streamforge.ui

import android.graphics.Paint
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class SceneLayer(val id:Long,val name:String,val kind:String,val text:String="",val x:Float=.08f,val y:Float=.08f,val opacity:Float=1f,val visible:Boolean=true,val locked:Boolean=false)
private data class SceneModel(val id:Long,val name:String,val layers:List<SceneLayer>)
private fun sceneDefaults()=listOf(
 SceneModel(1,"Starting Soon",listOf(SceneLayer(11,"Screen Capture","screen","",0f,0f),SceneLayer(12,"Title","text","STREAM STARTING SOON",.12f,.4f))),
 SceneModel(2,"Gameplay",listOf(SceneLayer(21,"Screen Capture","screen","",0f,0f),SceneLayer(22,"Channel label","text","PLAYING LIVE  •  STREAMFORGE",.04f,.05f))),
 SceneModel(3,"BRB",listOf(SceneLayer(31,"Screen Capture","screen","",0f,0f),SceneLayer(32,"BRB title","text","BE RIGHT BACK",.2f,.42f))),
 SceneModel(4,"Ending",listOf(SceneLayer(41,"Screen Capture","screen","",0f,0f),SceneLayer(42,"Ending title","text","THANKS FOR WATCHING",.12f,.4f)))
)
private fun encodeSceneList(items:List<SceneModel>):String { val a=JSONArray(); items.forEach{s->val l=JSONArray();s.layers.forEach{x->l.put(JSONObject().apply{put("id",x.id);put("name",x.name);put("kind",x.kind);put("text",x.text);put("x",x.x);put("y",x.y);put("opacity",x.opacity);put("visible",x.visible);put("locked",x.locked)})};a.put(JSONObject().apply{put("id",s.id);put("name",s.name);put("layers",l)})};return a.toString() }
private fun decodeSceneList(raw:String):List<SceneModel> = runCatching { val a=JSONArray(raw);(0 until a.length()).map{i->val s=a.getJSONObject(i);val l=s.optJSONArray("layers")?:JSONArray();SceneModel(s.optLong("id",i.toLong()),s.optString("name","Scene"),(0 until l.length()).map{j->val x=l.getJSONObject(j);SceneLayer(x.optLong("id"),x.optString("name"),x.optString("kind"),x.optString("text"),x.optDouble("x",.08).toFloat(),x.optDouble("y",.08).toFloat(),x.optDouble("opacity",1.0).toFloat(),x.optBoolean("visible",true),x.optBoolean("locked",false))})} }.getOrDefault(sceneDefaults()).ifEmpty{sceneDefaults()}

@Composable
fun SceneStudio(){
 val ctx= LocalContext.current
 val prefs= remember{ctx.getSharedPreferences("streamforge_studio",0)}
 val scenes= remember{mutableStateListOf<SceneModel>().apply{addAll(decodeSceneList(prefs.getString("scenes","")?:""))}}
 var active by remember{mutableIntStateOf(0)}
 var selected by remember{mutableLongStateOf(-1L)}
 var addDialog by remember{mutableStateOf(false)}
 var renameDialog by remember{mutableStateOf(false)}
 var draft by remember{mutableStateOf("")}
 LaunchedEffect(scenes.toList()){prefs.edit().putString("scenes",encodeSceneList(scenes)).apply()}
 val index=active.coerceIn(0,scenes.lastIndex); val scene=scenes[index]; val layer=scene.layers.firstOrNull{it.id==selected}
 fun update(s:SceneModel){scenes[index]=s}
 fun add(kind:String){val n=scene.layers.count{it.kind==kind}+1;val item=SceneLayer(System.nanoTime(),"${kind.replaceFirstChar{it.uppercase()}} $n",kind,if(kind=="text")"YOUR TEXT" else "",if(kind=="screen")0f else .08f,if(kind=="screen")0f else .08f);update(scene.copy(layers=scene.layers+item));selected=item.id}
 Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)){
  Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("SCENE STUDIO",color=Color.White,fontSize=17.sp);Text("Scenes · Sources · Layer controls",color=Color(0xFF9AA6B8),fontSize=11.sp)};TextButton(onClick={scenes.add(SceneModel(System.nanoTime(),"Scene ${scenes.size+1}",emptyList()));active=scenes.lastIndex;selected=-1}){Text("+ Scene")}}
  Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)){scenes.forEachIndexed{i,s->FilterChip(selected=i==index,onClick={active=i;selected=-1},label={Text(s.name)})}}
  Surface(color=Color(0xFF151B25),shape=RoundedCornerShape(15.dp),modifier=Modifier.fillMaxWidth()){
   Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
    Row(verticalAlignment=Alignment.CenterVertically){Text("CANVAS PREVIEW",color=Color.White,fontSize=11.sp,modifier=Modifier.weight(1f));Text("16:9",color=Color.Gray,fontSize=10.sp)}
    Canvas(Modifier.fillMaxWidth().aspectRatio(16f/9f).background(Color(0xFF0B1220),RoundedCornerShape(9.dp))){
     drawRect(Color(0xFF111D30))
     drawIntoCanvas{c->val p=Paint(3);p.typeface=Typeface.create("sans-serif",Typeface.BOLD);p.textSize=size.width*.035f;p.color=android.graphics.Color.LTGRAY;c.nativeCanvas.drawText("STREAMFORGE  •  ${scene.name}",size.width*.04f,size.height*.13f,p)
      scene.layers.filter{it.visible}.forEach{x->val left=x.x*size.width;val top=x.y*size.height;p.alpha=(x.opacity*255).toInt();p.color=when(x.kind){"text"->android.graphics.Color.rgb(48,72,120);"camera"->android.graphics.Color.rgb(54,83,103);"image"->android.graphics.Color.rgb(80,66,116);else->android.graphics.Color.rgb(25,42,65)};c.nativeCanvas.drawRoundRect(left,top,left+size.width*.52f,top+size.height*.19f,10f,10f,p);p.color=android.graphics.Color.WHITE;p.textSize=size.width*.026f;c.nativeCanvas.drawText((if(x.kind=="text")x.text else "${x.kind.uppercase()} · ${x.name}").take(32),left+10,top+size.height*.12f,p);if(x.id==selected){p.style=Paint.Style.STROKE;p.strokeWidth=3f;p.color=android.graphics.Color.CYAN;c.nativeCanvas.drawRoundRect(left,top,left+size.width*.52f,top+size.height*.19f,10f,10f,p);p.style=Paint.Style.FILL}}
     }
    }
    Text("Scene layout preview. Actual encoder compositing is the next integration milestone.",color=Color(0xFF9AA6B8),fontSize=10.sp)
   }
  }
  Row(verticalAlignment=Alignment.CenterVertically){Text("SOURCES / LAYERS",color=Color(0xFF9AA6B8),fontSize=11.sp,modifier=Modifier.weight(1f));TextButton(onClick={addDialog=true}){Text("+ Add source")}}
  scene.layers.asReversed().forEach{x->Surface(color=if(x.id==selected)Color(0xFF25324A) else Color(0xFF151B25),shape=RoundedCornerShape(10.dp),modifier=Modifier.fillMaxWidth(),onClick={selected=x.id}){Row(Modifier.padding(9.dp),verticalAlignment=Alignment.CenterVertically){Text(if(x.visible)"◉" else "○",color=Color(0xFF8EA5FF));Column(Modifier.weight(1f).padding(start=8.dp)){Text(x.name,color=Color.White,fontSize=12.sp);Text(x.kind.uppercase(),color=Color.Gray,fontSize=9.sp)};TextButton(onClick={update(scene.copy(layers=scene.layers.map{if(it.id==x.id)it.copy(visible=!it.visible)else it}))}){Text(if(x.visible)"Hide" else "Show")};TextButton(onClick={update(scene.copy(layers=scene.layers.map{if(it.id==x.id)it.copy(locked=!it.locked)else it}))}){Text(if(x.locked)"Unlock" else "Lock")}}}}
  if(layer!=null) Surface(color=Color(0xFF151B25),shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
   Text("EDIT · ${layer.name}",color=Color.White,fontSize=12.sp)
   if(layer.kind=="text") OutlinedTextField(value=layer.text,onValueChange = {v->update(scene.copy(layers=scene.layers.map{if(it.id==layer.id)it.copy(text=v)else it}))},label={Text("Overlay text")},modifier=Modifier.fillMaxWidth())
   Text("Opacity · ${(layer.opacity*100).toInt()}%",color=Color.White,fontSize=11.sp);Slider(value=layer.opacity,onValueChange = {v->update(scene.copy(layers=scene.layers.map{if(it.id==layer.id)it.copy(opacity=v)else it}))})
   Text("Horizontal position · ${(layer.x*100).toInt()}%",color=Color.White,fontSize=11.sp);Slider(value=layer.x,onValueChange = {v->update(scene.copy(layers=scene.layers.map{if(it.id==layer.id&&!it.locked)it.copy(x=v)else it}))})
   Text("Vertical position · ${(layer.y*100).toInt()}%",color=Color.White,fontSize=11.sp);Slider(value=layer.y,onValueChange = {v->update(scene.copy(layers=scene.layers.map{if(it.id==layer.id&&!it.locked)it.copy(y=v)else it}))})
   Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){OutlinedButton(onClick={val a=scene.layers.toMutableList();val i=a.indexOfFirst{it.id==layer.id};if(i>0){val t=a.removeAt(i);a.add(i-1,t);update(scene.copy(layers=a))}},modifier=Modifier.weight(1f)){Text("Up")};OutlinedButton(onClick={val a=scene.layers.toMutableList();val i=a.indexOfFirst{it.id==layer.id};if(i>=0&&i<a.lastIndex){val t=a.removeAt(i);a.add(i+1,t);update(scene.copy(layers=a))}},modifier=Modifier.weight(1f)){Text("Down")};OutlinedButton(onClick={update(scene.copy(layers=scene.layers.filterNot{it.id==layer.id}));selected=-1}){Text("Delete")}}
  }}
  Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){OutlinedButton(onClick={draft=scene.name;renameDialog=true},modifier=Modifier.weight(1f)){Text("Rename")};OutlinedButton(onClick={scenes.add(scene.copy(id=System.nanoTime(),name="${scene.name} Copy",layers=scene.layers.map{it.copy(id=System.nanoTime())}));active=scenes.lastIndex},modifier=Modifier.weight(1f)){Text("Duplicate")};OutlinedButton(onClick={if(scenes.size>1){scenes.removeAt(index);active=(index-1).coerceAtLeast(0);selected=-1}},enabled=scenes.size>1){Text("Delete")}}
  Text("Scene collection auto-saves on this device.",color=Color.Gray,fontSize=10.sp)
 }
 if(addDialog) AlertDialog(onDismissRequest={addDialog=false},title={Text("Add source")},text={Column{listOf("screen" to "Screen capture","text" to "Text overlay","image" to "Image layer","camera" to "Camera layer").forEach{(k,v)->TextButton(onClick={add(k);addDialog=false}){Text(v)}}}},confirmButton={TextButton(onClick={addDialog=false}){Text("Close")}})
 if(renameDialog) AlertDialog(onDismissRequest={renameDialog=false},title={Text("Rename scene")},text={OutlinedTextField(value=draft,onValueChange={draft=it},singleLine=true)},confirmButton={TextButton(onClick={if(draft.isNotBlank())scenes[index]=scene.copy(name=draft.trim());renameDialog=false}){Text("Save")}},dismissButton={TextButton(onClick={renameDialog=false}){Text("Cancel")}})
}
