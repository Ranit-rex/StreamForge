const servers={youtube:"rtmps://a.rtmp.youtube.com/live2",kick:"rtmps://fa723fc1b171.global-contribute.live-video.net/app",twitch:"rtmps://live.twitch.tv/app",custom:""};
const serverInput=document.querySelector("#server");
document.querySelectorAll("[data-platform]").forEach(button=>button.addEventListener("click",()=>{
 document.querySelectorAll("[data-platform]").forEach(item=>item.classList.remove("active"));
 button.classList.add("active");serverInput.value=servers[button.dataset.platform];
}));
document.querySelector("#copyServer").addEventListener("click",async()=>{
 try{await navigator.clipboard.writeText(serverInput.value);document.querySelector("#copyServer").textContent="Copied";setTimeout(()=>document.querySelector("#copyServer").textContent="Copy server URL",1400);}
 catch{serverInput.focus();serverInput.select();}
});
let installPrompt;
window.addEventListener("beforeinstallprompt",event=>{event.preventDefault();installPrompt=event;document.querySelector("#install").hidden=false;});
document.querySelector("#install").addEventListener("click",async()=>{if(!installPrompt)return;installPrompt.prompt();await installPrompt.userChoice;installPrompt=null;document.querySelector("#install").hidden=true;});
if("serviceWorker" in navigator)window.addEventListener("load",()=>navigator.serviceWorker.register("./sw.js"));
