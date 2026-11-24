package app.streamforge.ui

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private data class Provider(val name:String,val prefix:String,val url:String,val kind:String,val free:Boolean=false)
private data class Model(val id:String,val name:String)
private data class ChatMsg(val role:String,val text:String)

private val providers = listOf(
 Provider("OpenRouter","sk-or-v1-","https://openrouter.ai/api/v1","openai",true),
 Provider("Google Gemini","AIza","https://generativelanguage.googleapis.com/v1beta","gemini"),
 Provider("Groq","gsk_","https://api.groq.com/openai/v1","openai"),
 Provider("Anthropic Claude","sk-ant-","https://api.anthropic.com/v1","anthropic"),
 Provider("Fireworks AI","fw_","https://api.fireworks.ai/inference/v1","openai"),
 Provider("Hugging Face","hf_","https://router.huggingface.co/v1","openai"),
 Provider("Cerebras","csk-","https://api.cerebras.ai/v1","openai"),
 Provider("OpenAI","sk-proj-","https://api.openai.com/v1","openai"),
 Provider("OpenAI","sk-svcacct-","https://api.openai.com/v1","openai")
)

private object KeyVault {
 private const val alias="streamforge_ai_key"
 private fun key():SecretKey {
  val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
  (ks.getKey(alias,null) as? SecretKey)?.let{return it}
  val gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
  gen.init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
   .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
  return gen.generateKey()
 }
 fun save(c:Context,s:String){
  val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
  val prefs=c.getSharedPreferences("sf_ai",0)
  prefs.edit().putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP))
   .putString("data",Base64.encodeToString(cipher.doFinal(s.toByteArray()),Base64.NO_WRAP)).apply()
 }
 fun read(c:Context):String=runCatching{
  val p=c.getSharedPreferences("sf_ai",0);val iv=Base64.decode(p.getString("iv",""),Base64.NO_WRAP)
  val data=Base64.decode(p.getString("data",""),Base64.NO_WRAP)
  val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,iv))
  String(cipher.doFinal(data))
 }.getOrDefault("")
 fun clear(c:Context){c.getSharedPreferences("sf_ai",0).edit().clear().apply()}
}

@Composable
fun AiAssistantPanel(){
 val context=androidx.compose.ui.platform.LocalContext.current
 val scope=rememberCoroutineScope()
 var apiKey by remember{mutableStateOf("")};var provider by remember{mutableStateOf<Provider?>(null)}
 var models by remember{mutableStateOf(listOf<Model>())};var selected by remember{mutableStateOf<Model?>(null)}
 var messages by remember{mutableStateOf(listOf<ChatMsg>())};var prompt by remember{mutableStateOf("")}
 var showKey by remember{mutableStateOf(false)};var busy by remember{mutableStateOf(false)}
 var connected by remember{mutableStateOf(false)};var status by remember{mutableStateOf("Paste an API key to connect.")}
 LaunchedEffect(Unit){
  val saved=KeyVault.read(context)
  if(saved.isNotBlank()){
   apiKey=saved;val p=providers.firstOrNull{saved.startsWith(it.prefix)}
   if(p!=null){provider=p;busy=true;try{models=fetchModels(p,saved);selected=models.firstOrNull();connected=models.isNotEmpty();status=if(connected)"Connected · "+p.name else "No models available."}catch(e:Exception){status=e.message?: "Reconnect failed."}finally{busy=false}}
  }
 }
 Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)){
  Text("PLAY WITH AI",color=Color(0xFF9DB4FF),fontSize=10.sp,fontWeight=FontWeight.Black)
  Text("AI Assistant",color=Color.White,fontSize=23.sp,fontWeight=FontWeight.Bold)
  Text("Paste any supported provider key. Provider and model catalogue load automatically.",color=Color(0xFF91A0B4),fontSize=12.sp)
  Surface(color=Color(0xFF121923),shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth()){
   Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
    OutlinedTextField(apiKey,{apiKey=it.trim();connected=false},modifier=Modifier.fillMaxWidth(),singleLine=true,
     label={Text("API key")},visualTransformation=if(showKey)VisualTransformation.None else PasswordVisualTransformation(),
     keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),
     trailingIcon={TextButton(onClick={showKey=!showKey}){Text(if(showKey)"HIDE" else "SHOW")}})
    Button(onClick={
     val p=providers.firstOrNull{apiKey.startsWith(it.prefix)}
     if(p==null){status="Can't identify this key safely. Recognized formats: OpenRouter, Gemini, Groq, Claude, Fireworks, Hugging Face, Cerebras and OpenAI project/service keys."}
     else{provider=p;busy=true;status="Detecting provider and loading models…";scope.launch{
      try{val found=fetchModels(p,apiKey);if(found.isEmpty())error("No models returned. Check key permissions.")
       KeyVault.save(context,apiKey);models=found;selected=found.first();messages=emptyList();connected=true;status="Connected · "+p.name+" · "+found.size+" models"
      }catch(e:Exception){connected=false;status=e.message?:"Connection failed."}finally{busy=false}
     }}
    },enabled=apiKey.isNotBlank()&&!busy,modifier=Modifier.fillMaxWidth()){Text(if(busy)"CONNECTING…" else "AUTO-DETECT & CONNECT")}
    Text(status,color=if(connected)Color(0xFF55D6A1) else Color(0xFF91A0B4),fontSize=10.sp)
    if(connected&&provider!=null){
     Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
      Text("PROVIDER  "+provider!!.name,color=Color.White,fontSize=12.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
      TextButton(onClick={KeyVault.clear(context);apiKey="";provider=null;models=emptyList();selected=null;messages=emptyList();connected=false;status="Key removed."}){Text("REMOVE")}
     }
     var expanded by remember{mutableStateOf(false)}
     Box{OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()){
      Text(selected?.name?:"Choose model",modifier=Modifier.weight(1f));Text("⌄")
     };DropdownMenu(expanded,{expanded=false}){models.forEach{m->DropdownMenuItem(text={Text(m.name)},onClick={selected=m;expanded=false})}}}
     Text(if(provider!!.free)"OpenRouter free models only." else "Model access and pricing depend on provider account.",color=Color(0xFF91A0B4),fontSize=9.sp)
    }
   }
  }
  if(connected&&selected!=null){
   Text("CHAT",color=Color(0xFF9DB4FF),fontSize=10.sp,fontWeight=FontWeight.Black)
   Surface(color=Color(0xFF121923),shape=RoundedCornerShape(17.dp),modifier=Modifier.fillMaxWidth()){
    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
     LazyColumn(Modifier.fillMaxWidth().heightIn(max=340.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
      items(messages){m->Surface(color=if(m.role=="user")Color(0xFF26354B) else Color(0xFF192331),shape=RoundedCornerShape(10.dp)){
       Text(m.text,Modifier.fillMaxWidth().padding(10.dp),color=Color.White,fontSize=12.sp)
      }}
     }
     OutlinedTextField(prompt,{prompt=it},modifier=Modifier.fillMaxWidth(),label={Text("Message AI")},minLines=2,maxLines=5)
     Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
      OutlinedButton(onClick={messages=emptyList()},enabled=messages.isNotEmpty()&&!busy,modifier=Modifier.weight(1f)){Text("NEW CHAT")}
      Button(onClick={
       val q=prompt.trim();val p=provider;val m=selected
       if(q.isNotEmpty()&&p!=null&&m!=null){prompt="";val history=messages+ChatMsg("user",q);messages=history;busy=true
        scope.launch{try{messages=history+ChatMsg("assistant",sendChat(p,apiKey,m,history))}catch(e:Exception){messages=history+ChatMsg("assistant","Error: "+(e.message?:"Request failed"))}finally{busy=false}}
       }
      },enabled=prompt.isNotBlank()&&!busy,modifier=Modifier.weight(1f)){Text(if(busy)"WAIT…" else "SEND")}
     }
    }
   }
  }
  Text("API key is encrypted with Android Keystore and sent only to the detected provider.",color=Color(0xFF91A0B4),fontSize=9.sp)
 }
}

private fun offlineAssistantReply(question: String, appContext: String): Pair<String, String?> {
 val q = question.lowercase()
 val live = appContext.contains("streaming=true")
 val rec = appContext.contains("recording=true")
 return when {
  (q.contains("open") || q.contains("go to") || q.contains("show")) && (q.contains("scene")) ->
   "Opening Scenes. You can add and arrange layers there." to "SCENES"
  (q.contains("open") || q.contains("go to") || q.contains("show")) && (q.contains("recording")) ->
   "Opening Recordings." to "RECORDINGS"
  (q.contains("open") || q.contains("go to") || q.contains("show")) && (q.contains("setting")) ->
   "Opening Settings." to "SETTINGS"
  (q.contains("open") || q.contains("go to") || q.contains("show")) && (q.contains("studio") || q.contains("live") || q.contains("stream")) ->
   "Opening Studio." to "STUDIO"
  q.contains("stop") && (q.contains("record") || q.contains("stream") || q.contains("live")) ->
   "I can guide the stop action, but I won't end a recording or broadcast without your confirmation. Open Studio and use STOP REC or END LIVE." to "STUDIO"
  q.contains("start") && (q.contains("record") || q.contains("stream") || q.contains("live")) ->
   "To start safely, open Studio. Choose your capture source and quality, then tap RECORD or GO LIVE. Android will ask for screen-capture permission." to "STUDIO"
  q.contains("slow") || q.contains("lag") || q.contains("freeze") || q.contains("crash") ->
   "For smoother output, start with 720p at 30 FPS, keep no more than two simultaneous video layers, and close other heavy apps. If it still lags, lower the video overlay frame rate to 15 FPS." to null
  q.contains("status") || q.contains("what am i") || q.contains("current") ->
   "Current app status: ${if (live) "LIVE streaming" else "not streaming"}; ${if (rec) "recording is active" else "no local recording"}. I can open Studio, Scenes, Recordings, or Settings." to null
  q.contains("scene") || q.contains("layer") || q.contains("overlay") ->
   "In Scenes, add a layer, choose its type, then adjust its position, size, opacity and order. For stable mobile playback, two video layers are supported simultaneously; other layer types can still be stacked." to "SCENES"
  q.contains("record") || q.contains("stream") || q.contains("broadcast") ->
   "Studio controls capture and output. You can choose APP or DISPLAY capture, set resolution/FPS, then start a local recording or RTMP broadcast." to "STUDIO"
  q.contains("help") || q.contains("what can") || q.contains("hello") || q.contains("hi") ->
   "Hey! I'm StreamForge's built-in assistant. I work offline without an API key. Ask about streaming, recording, scenes, layers, performance, or say 'open settings'." to null
  else ->
   "I can help with StreamForge's Studio, Scenes, Recordings, Settings and performance. Tell me what you want to do, or say 'open scenes' / 'open studio'. This offline build uses app-specific decision rules; a full generative on-device model is a separate model download." to null
 }
}

@Composable
fun MiniAiAssistant(appContext: String, onOpenFull: () -> Unit, onAction: (String) -> Unit) {
 var messages by remember { mutableStateOf(listOf(ChatMsg("assistant", "Hi! I'm your built-in StreamForge assistant. No API key needed. Ask me to open a section or help with your stream."))) }
 var prompt by remember { mutableStateOf("") }
 Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
  Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
   Column(Modifier.weight(1f)) {
    Text("BUILT-IN · OFFLINE", color = Color(0xFF55D6A1), fontSize = 9.sp, fontWeight = FontWeight.Bold)
    Text("I can help operate StreamForge", color = Color.White, fontSize = 12.sp)
   }
   TextButton(onClick = { messages = listOf(ChatMsg("assistant", "Chat cleared. What should we do?")) }) { Text("NEW CHAT") }
  }
  LazyColumn(Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 340.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
   items(messages) { msg ->
    Surface(color = if (msg.role == "user") Color(0xFF26354B) else Color(0xFF192331), shape = RoundedCornerShape(10.dp)) {
     Text(msg.text, Modifier.fillMaxWidth().padding(9.dp), color = Color.White, fontSize = 11.sp)
    }
   }
  }
  OutlinedTextField(
   value = prompt, onValueChange = { prompt = it }, modifier = Modifier.fillMaxWidth(),
   singleLine = false, minLines = 1, maxLines = 3, label = { Text("Ask StreamForge…") }
  )
  Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
   OutlinedButton(onClick = { onAction("STUDIO") }, modifier = Modifier.weight(1f)) { Text("STUDIO") }
   OutlinedButton(onClick = { onAction("SCENES") }, modifier = Modifier.weight(1f)) { Text("SCENES") }
   Button(
    onClick = {
     val q = prompt.trim()
     if (q.isNotEmpty()) {
      messages = messages + ChatMsg("user", q)
      prompt = ""
      val (answer, action) = offlineAssistantReply(q, appContext)
      messages = messages + ChatMsg("assistant", answer)
      if (action != null) onAction(action)
     }
    },
    enabled = prompt.isNotBlank(), modifier = Modifier.weight(1f)
   ) { Text("SEND") }
  }
  Text("Runs on this device. It won't start or stop a broadcast without your confirmation.", color = Color(0xFF91A0B4), fontSize = 9.sp)
 }
}

private suspend fun fetchModels(p:Provider,key:String):List<Model> = withContext(Dispatchers.IO){
 val c=URL(p.url+"/models").openConnection() as HttpURLConnection
 try{
  c.connectTimeout=15000;c.readTimeout=20000
  when(p.kind){"gemini"->c.setRequestProperty("x-goog-api-key",key);"anthropic"->{c.setRequestProperty("x-api-key",key);c.setRequestProperty("anthropic-version","2023-06-01")};else->c.setRequestProperty("Authorization","Bearer "+key)}
  val code=c.responseCode;val body=(if(code in 200..299)c.inputStream else c.errorStream).bufferedReader().use{it.readText()}
  if(code !in 200..299)throw IllegalStateException(apiError(code,body))
  val root=JSONObject(body);val arr=if(p.kind=="gemini")root.optJSONArray("models") else root.optJSONArray("data")
  val out=mutableListOf<Model>()
  if(arr!=null)for(i in 0 until arr.length()){
   val item=arr.optJSONObject(i)?:continue
   val id=if(p.kind=="gemini")item.optString("name") else item.optString("id")
   if(id.isBlank()||id=="null")continue
   if(p.free){val price=item.optJSONObject("pricing");if(!id.endsWith(":free")&&!(price!=null&&price.optString("prompt")=="0"&&price.optString("completion")=="0"))continue}
   out.add(Model(id,item.optString("name").takeIf{it.isNotBlank()&&it!="null"}?:id))
  }
  out.distinctBy{it.id}.sortedBy{it.name.lowercase()}
 }finally{c.disconnect()}
}

private suspend fun sendChat(p:Provider,key:String,model:Model,history:List<ChatMsg>,systemPrompt:String=""):String=withContext(Dispatchers.IO){
 val endpoint=when(p.kind){"gemini"->p.url+"/"+model.id+":generateContent";"anthropic"->p.url+"/messages";else->p.url+"/chat/completions"}
 val c=URL(endpoint).openConnection() as HttpURLConnection
 try{
  c.requestMethod="POST";c.doOutput=true;c.connectTimeout=20000;c.readTimeout=90000;c.setRequestProperty("Content-Type","application/json")
  when(p.kind){"gemini"->c.setRequestProperty("x-goog-api-key",key);"anthropic"->{c.setRequestProperty("x-api-key",key);c.setRequestProperty("anthropic-version","2023-06-01")};else->c.setRequestProperty("Authorization","Bearer "+key)}
  val body=when(p.kind){
   "gemini"->{val a=JSONArray();history.forEach{a.put(JSONObject().put("role",if(it.role=="assistant")"model" else "user").put("parts",JSONArray().put(JSONObject().put("text",it.text)) ))};JSONObject().put("systemInstruction",JSONObject().put("parts",JSONArray().put(JSONObject().put("text",systemPrompt)))).put("contents",a)}
   "anthropic"->{val a=JSONArray();history.forEach{a.put(JSONObject().put("role",it.role).put("content",it.text))};JSONObject().put("model",model.id).put("max_tokens",2048).put("system",systemPrompt).put("messages",a)}
   else->{val a=JSONArray();if(systemPrompt.isNotBlank())a.put(JSONObject().put("role","system").put("content",systemPrompt));history.forEach{a.put(JSONObject().put("role",it.role).put("content",it.text))};JSONObject().put("model",model.id).put("messages",a)}
  }
  c.outputStream.use{it.write(body.toString().toByteArray())}
  val code=c.responseCode;val response=(if(code in 200..299)c.inputStream else c.errorStream).bufferedReader().use{it.readText()}
  if(code !in 200..299)throw IllegalStateException(apiError(code,response))
  val root=JSONObject(response)
  when(p.kind){"gemini"->root.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
   "anthropic"->root.optJSONArray("content")?.optJSONObject(0)?.optString("text")
   else->root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
  }?.takeIf{!it.isNullOrBlank()&&it!="null"}?:"Empty response from provider."
 }finally{c.disconnect()}
}
private fun apiError(code:Int,body:String):String{
 val detail=runCatching{JSONObject(body).optJSONObject("error")?.optString("message")}.getOrNull()
 return when(code){401,403->"Invalid API key or permission denied.";402->"Provider requires credits or payment.";429->"Rate limit reached. Try again shortly.";else->detail?.takeIf{it.isNotBlank()}?:"Provider request failed (HTTP "+code+")."}
}
