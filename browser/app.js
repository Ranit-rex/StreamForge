const $ = id => document.getElementById(id);
const state = { display:null, mic:null, combined:null, recorder:null, chunks:[], peer:null, whipResource:null, live:false, audioContext:null, audioDestination:null, mixSources:[] };
const say = message => { $("message").textContent = message; };
const setStatus = (label, live=false) => { $("statusText").textContent=label; $("statusDot").classList.toggle("live",live); };
function refresh(){
 const hasVideo=!!state.display;
 $("captureState").textContent=hasVideo?"SCREEN SHARED":"NO SOURCE";
 $("captureState").classList.toggle("active",hasVideo);
 $("emptyState").hidden=hasVideo;
 $("stopCapture").disabled=!hasVideo;
 $("startRecord").disabled=!hasVideo||!!state.recorder;
 $("stopRecord").disabled=!state.recorder;
 $("goLive").disabled=!hasVideo||state.live||!$("whipUrl").value.trim();
 $("videoInfo").textContent=hasVideo?"READY":"—";
 $("micInfo").textContent=state.mic?"ON":"OFF";
 $("recordInfo").textContent=state.recorder?"RECORDING":"OFF";
 $("toggleMic").textContent=state.mic?"Mic: on":"Mic: off";
}
async function buildCombined(){
 if(!state.display)return;
 if(state.audioContext){try{await state.audioContext.close();}catch{}}
 state.audioContext=null;state.audioDestination=null;state.mixSources=[];
 const videoTracks=state.display.getVideoTracks();
 const audioTracks=[...state.display.getAudioTracks(),...(state.mic?state.mic.getAudioTracks():[])];
 let outputAudio=[];
 if(audioTracks.length>1 && window.AudioContext){
  try{
   const ctx=new AudioContext();const dest=ctx.createMediaStreamDestination();
   audioTracks.forEach(track=>{const source=ctx.createMediaStreamSource(new MediaStream([track]));source.connect(dest);state.mixSources.push(source);});
   await ctx.resume();state.audioContext=ctx;state.audioDestination=dest;outputAudio=dest.stream.getAudioTracks();
  }catch(e){console.warn("Audio mix fallback",e);outputAudio=audioTracks.slice(0,1);}
 }else outputAudio=audioTracks;
 state.combined=new MediaStream([...videoTracks,...outputAudio]);
 $("preview").srcObject=state.display;
 refresh();
}
async function shareScreen(){
 if(!window.isSecureContext){say("Open StreamForge over HTTPS to enable screen capture.");return;}
 if(!navigator.mediaDevices?.getDisplayMedia){say("This browser does not support screen sharing. Try the latest Chrome or Edge.");return;}
 try{
  state.display=await navigator.mediaDevices.getDisplayMedia({video:{frameRate:{ideal:30,max:60}},audio:true});
  state.display.getVideoTracks()[0]?.addEventListener("ended",stopCapture,{once:true});
  await buildCombined();setStatus("SCREEN READY");say("Screen is shared. Enable your mic or start recording.");
 }catch(e){if(e.name!=="NotAllowedError")say("Screen capture failed: "+e.message);}
 refresh();
}
async function toggleMic(){
 if(state.mic){state.mic.getTracks().forEach(t=>t.stop());state.mic=null;}
 else{
  if(!navigator.mediaDevices?.getUserMedia){say("Microphone capture is not supported in this browser.");return;}
  try{state.mic=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true,autoGainControl:true}});}
  catch(e){say("Microphone permission failed: "+e.message);return;}
 }
 if(state.display)await buildCombined();
 say(state.mic?"Microphone enabled.":"Microphone muted.");refresh();
}
function stopCapture(){
 if(state.live)endBroadcast();
 if(state.recorder)stopRecording();
 state.display?.getTracks().forEach(t=>t.stop());state.display=null;
 if(state.mic){state.mic.getTracks().forEach(t=>t.stop());state.mic=null;}
 if(state.audioContext){state.audioContext.close().catch(()=>{});state.audioContext=null;}
 state.combined=null;$("preview").srcObject=null;setStatus("STUDIO READY");say("Capture stopped.");refresh();
}
function chooseMime(){
 if(!window.MediaRecorder)return "";
 return ["video/webm;codecs=vp9,opus","video/webm;codecs=vp8,opus","video/webm"].find(t=>MediaRecorder.isTypeSupported(t))||"";
}
function startRecording(){
 if(!state.combined||!window.MediaRecorder)return;
 try{
  state.chunks=[];
  const mime=chooseMime();
  state.recorder=new MediaRecorder(state.combined,mime?{mimeType:mime}:{});
  state.recorder.ondataavailable=e=>{if(e.data?.size)state.chunks.push(e.data);};
  state.recorder.onerror=e=>say("Recording error: "+(e.error?.message||"unknown error"));
  state.recorder.onstop=()=>{
   const blob=new Blob(state.chunks,{type:state.recorder?.mimeType||"video/webm"});
   if(blob.size){
    const url=URL.createObjectURL(blob),a=document.createElement("a");
    a.href=url;a.download="StreamForge-"+new Date().toISOString().replace(/[:.]/g,"-")+".webm";
    document.body.appendChild(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),60000);
    say("Recording saved as a WebM file.");
   }else say("No recording data was produced.");
   state.recorder=null;refresh();
  };
  state.recorder.start(1000);say("Recording locally. Press Stop & save when finished.");refresh();
 }catch(e){state.recorder=null;say("Recording could not start: "+e.message);refresh();}
}
function stopRecording(){if(state.recorder&&state.recorder.state!=="inactive")state.recorder.stop();}
async function waitIce(pc){
 if(pc.iceGatheringState==="complete")return;
 await new Promise(resolve=>{
  const finish=()=>{clearTimeout(timer);pc.removeEventListener("icegatheringstatechange",check);resolve();};
  const check=()=>{if(pc.iceGatheringState==="complete")finish();};
  const timer=setTimeout(finish,5000);pc.addEventListener("icegatheringstatechange",check);
 });
}
async function startBroadcast(){
 const endpoint=$("whipUrl").value.trim(),token=$("streamToken").value.trim();
 if(!endpoint||!state.combined)return;
 let parsed;try{parsed=new URL(endpoint);}catch{say("Enter a valid WHIP endpoint URL.");return;}
 if(parsed.protocol!=="https:" && parsed.hostname!=="localhost"){say("WHIP endpoint must use HTTPS.");return;}
 try{
  setStatus("CONNECTING");say("Connecting to WHIP destination…");
  const pc=new RTCPeerConnection({iceServers:[{urls:"stun:stun.l.google.com:19302"}]});state.peer=pc;
  state.combined.getTracks().forEach(track=>pc.addTrack(track,state.combined));
  pc.onconnectionstatechange=()=>{
   if(pc.connectionState==="connected"){setStatus("BROADCASTING",true);say("You are live.");}
   if(["failed","disconnected"].includes(pc.connectionState)&&state.live){setStatus("CONNECTION LOST");say("Connection interrupted. Check the network and ingest service.");}
  };
  const offer=await pc.createOffer();await pc.setLocalDescription(offer);await waitIce(pc);
  const headers={"Content-Type":"application/sdp","Accept":"application/sdp"};if(token)headers.Authorization="Bearer "+token;
  const response=await fetch(endpoint,{method:"POST",headers,body:pc.localDescription.sdp});
  if(!response.ok)throw new Error("WHIP server returned HTTP "+response.status);
  const answer=await response.text();if(!answer.trim())throw new Error("WHIP server returned an empty SDP answer.");
  const location=response.headers.get("Location");if(location)state.whipResource=new URL(location,endpoint).href;
  await pc.setRemoteDescription({type:"answer",sdp:answer});
  state.live=true;$("goLive").hidden=true;$("endLive").hidden=false;refresh();
 }catch(e){
  state.peer?.close();state.peer=null;state.live=false;setStatus("STUDIO READY");
  say("Broadcast failed: "+e.message+". Check endpoint, token, CORS and WHIP support.");
  refresh();
 }
}
async function endBroadcast(){
 const wasLive=state.live;state.live=false;
 if(state.whipResource){
  try{const headers={};const token=$("streamToken").value.trim();if(token)headers.Authorization="Bearer "+token;await fetch(state.whipResource,{method:"DELETE",headers});}catch(e){console.warn("WHIP resource cleanup failed",e);}
 }
 state.peer?.close();state.peer=null;state.whipResource=null;
 $("goLive").hidden=false;$("endLive").hidden=true;setStatus("STUDIO READY");say(wasLive?"Broadcast ended.":"Broadcast stopped.");refresh();
}
$("shareScreen").addEventListener("click",shareScreen);
$("stopCapture").addEventListener("click",stopCapture);
$("toggleMic").addEventListener("click",toggleMic);
$("startRecord").addEventListener("click",startRecording);
$("stopRecord").addEventListener("click",stopRecording);
$("goLive").addEventListener("click",startBroadcast);
$("endLive").addEventListener("click",endBroadcast);
$("whipUrl").addEventListener("input",refresh);
$("toggleToken").addEventListener("click",()=>{const input=$("streamToken"),show=input.type==="password";input.type=show?"text":"password";$("toggleToken").textContent=show?"Hide":"Show";});
$("copyEndpoint").addEventListener("click",async()=>{const value=$("whipUrl").value.trim();if(!value){say("Enter an endpoint URL first.");return;}try{await navigator.clipboard.writeText(value);say("Endpoint URL copied.");}catch{say("Clipboard access unavailable in this browser.");}});
$("platform").addEventListener("change",()=>{const p=$("platform").value;const hints={custom:"Enter the WHIP URL supplied by your ingest provider.",youtube:"Use a WHIP ingest URL from a supported YouTube workflow or gateway; standard RTMP URLs will not work here.",twitch:"Twitch requires a compatible WHIP gateway for this browser studio.",kick:"Kick requires a compatible WHIP gateway for this browser studio."};say(hints[p]);});
let installPrompt;
window.addEventListener("beforeinstallprompt",e=>{e.preventDefault();installPrompt=e;$("install").hidden=false;});
$("install").addEventListener("click",async()=>{if(!installPrompt)return;installPrompt.prompt();await installPrompt.userChoice;installPrompt=null;$("install").hidden=true;});
if("serviceWorker"in navigator)window.addEventListener("load",()=>navigator.serviceWorker.register("./sw.js").catch(e=>console.warn("Service worker",e)));
$("secureInfo").textContent=window.isSecureContext?"SECURE":"HTTPS REQUIRED";
$("secureInfo").style.color=window.isSecureContext?"var(--green)":"var(--red)";
$("captureSupport").textContent=navigator.mediaDevices?.getDisplayMedia?"AVAILABLE":"UNAVAILABLE";
$("recordSupport").textContent=window.MediaRecorder?"AVAILABLE":"UNAVAILABLE";
refresh();
