const $=id=>document.getElementById(id);
const state={display:null,mic:null,combined:null,recorder:null,chunks:[],peer:null,whipResource:null,live:false};
const setStatus=(label,live=false)=>{$("statusText").textContent=label;$("statusDot").classList.toggle("live",live)};
const refresh=()=>{
 const hasVideo=!!state.display;
 $("captureState").textContent=hasVideo?"SCREEN SHARED":"NO SOURCE";
 $("captureState").classList.toggle("active",hasVideo);
 $("emptyState").hidden=hasVideo;
 $("stopCapture").disabled=!hasVideo;
 $("startRecord").disabled=!hasVideo||!!state.recorder;
 $("goLive").disabled=!hasVideo||state.live||!$("whipUrl").value.trim();
 $("videoInfo").textContent=hasVideo?"READY":"—";
 $("micInfo").textContent=state.mic?"ON":"OFF";
 $("toggleMic").textContent=state.mic?"Mic: on":"Mic: off";
 $("recordInfo").textContent=state.recorder?"RECORDING":"OFF";
};
function buildCombined(){
 if(!state.display)return;
 const tracks=[...state.display.getVideoTracks(),...(state.mic?state.mic.getAudioTracks():[])];
 state.combined=new MediaStream(tracks);
 $("preview").srcObject=state.display;
 refresh();
}
async function shareScreen(){
 if(!navigator.mediaDevices?.getDisplayMedia){alert("Screen capture needs a supported browser and a secure HTTPS page.");return;}
 try{
  state.display=await navigator.mediaDevices.getDisplayMedia({video:{frameRate:{ideal:30,max:60}},audio:true});
  state.display.getVideoTracks()[0].addEventListener("ended",()=>stopCapture());
  buildCombined();setStatus("SCREEN READY");
 }catch(e){if(e.name!=="NotAllowedError")alert("Could not share screen: "+e.message);}
}
async function toggleMic(){
 if(state.mic){state.mic.getTracks().forEach(t=>t.stop());state.mic=null;}
 else{try{state.mic=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true,autoGainControl:true}});}catch(e){alert("Microphone permission failed: "+e.message);return;}}
 if(state.display)buildCombined();refresh();
}
function stopCapture(){
 if(state.live)endBroadcast();
 if(state.recorder)stopRecording();
 state.display?.getTracks().forEach(t=>t.stop());state.display=null;
 if(state.mic){state.mic.getTracks().forEach(t=>t.stop());state.mic=null;}
 $("preview").srcObject=null;setStatus("STUDIO READY");refresh();
}
function chooseMime(){return ["video/webm;codecs=vp9,opus","video/webm;codecs=vp8,opus","video/webm"].find(t=>MediaRecorder.isTypeSupported(t))||"";}
function startRecording(){
 if(!state.combined)return;
 try{
  state.chunks=[];state.recorder=new MediaRecorder(state.combined,{mimeType:chooseMime()});
  state.recorder.ondataavailable=e=>{if(e.data.size)state.chunks.push(e.data)};
  state.recorder.onstop=()=>{const blob=new Blob(state.chunks,{type:state.recorder?.mimeType||"video/webm"});const url=URL.createObjectURL(blob);const a=document.createElement("a");a.href=url;a.download="StreamForge-"+new Date().toISOString().replace(/[:.]/g,"-")+".webm";a.click();setTimeout(()=>URL.revokeObjectURL(url),30000);state.recorder=null;refresh();};
  state.recorder.start(1000);refresh();
 }catch(e){alert("Recording could not start: "+e.message);}
}
function stopRecording(){if(state.recorder&&state.recorder.state!=="inactive")state.recorder.stop();}
async function waitIce(pc){
 if(pc.iceGatheringState==="complete")return;
 await new Promise(resolve=>{const timeout=setTimeout(resolve,3500);pc.addEventListener("icegatheringstatechange",()=>{if(pc.iceGatheringState==="complete"){clearTimeout(timeout);resolve();}});});
}
async function startBroadcast(){
 const endpoint=$("whipUrl").value.trim(),token=$("streamToken").value.trim();
 if(!endpoint||!state.combined)return;
 try{
  const pc=new RTCPeerConnection({iceServers:[{urls:"stun:stun.l.google.com:19302"}]});state.peer=pc;
  state.combined.getTracks().forEach(track=>pc.addTrack(track,state.combined));
  const offer=await pc.createOffer();await pc.setLocalDescription(offer);await waitIce(pc);
  const headers={"Content-Type":"application/sdp","Accept":"application/sdp"};if(token)headers.Authorization="Bearer "+token;
  const response=await fetch(endpoint,{method:"POST",headers,body:pc.localDescription.sdp});
  if(!response.ok)throw new Error("WHIP server returned HTTP "+response.status);
  const answer=await response.text();const location=response.headers.get("Location");
  if(location)state.whipResource=new URL(location,endpoint).href;
  await pc.setRemoteDescription({type:"answer",sdp:answer});
  state.live=true;$("goLive").hidden=true;$("endLive").hidden=false;setStatus("BROADCASTING",true);refresh();
  pc.onconnectionstatechange=()=>{if(["failed","disconnected","closed"].includes(pc.connectionState)&&state.live){setStatus("CONNECTION "+pc.connectionState.toUpperCase());}};
 }catch(e){state.peer?.close();state.peer=null;state.live=false;alert("Broadcast failed: "+e.message+"\nCheck the WHIP URL, token and destination CORS support.");refresh();}
}
async function endBroadcast(){
 state.live=false;
 try{if(state.whipResource){const headers={};const token=$("streamToken").value.trim();if(token)headers.Authorization="Bearer "+token;await fetch(state.whipResource,{method:"DELETE",headers});}}catch{}
 state.peer?.getSenders().forEach(s=>s.track?.stop());state.peer?.close();state.peer=null;state.whipResource=null;
 $("goLive").hidden=false;$("endLive").hidden=true;setStatus("STUDIO READY");refresh();
}
$("shareScreen").addEventListener("click",shareScreen);$("stopCapture").addEventListener("click",stopCapture);$("toggleMic").addEventListener("click",toggleMic);
$("startRecord").addEventListener("click",startRecording);$("stopRecord").addEventListener("click",stopRecording);
$("goLive").addEventListener("click",startBroadcast);$("endLive").addEventListener("click",endBroadcast);$("whipUrl").addEventListener("input",refresh);
let installPrompt;window.addEventListener("beforeinstallprompt",e=>{e.preventDefault();installPrompt=e;$("install").hidden=false;});
$("install").addEventListener("click",async()=>{if(!installPrompt)return;installPrompt.prompt();await installPrompt.userChoice;installPrompt=null;$("install").hidden=true;});
if("serviceWorker"in navigator)window.addEventListener("load",()=>navigator.serviceWorker.register("./sw.js"));
refresh();