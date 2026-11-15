import {WerewolfMusic} from './werewolf-music.js';

// Full-length user-provided tracks, with a separate switch for each game.
export const gameTracks=Object.freeze({
 drawguess:Object.freeze([1,2,3,4].map(n=>`/assets/audio/starlit-studio-${n}.mp3`)),
 heartprison:Object.freeze([1,2,3].map(n=>`/assets/audio/seventh-seat-${n}.mp3`)),
 script:Object.freeze([1,2,3,4].map(n=>`/assets/audio/story-unfinished-${n}.mp3`)),
});
export function gameMusic(game,notify){
 if(!gameTracks[game])throw new Error('Unknown tabletop music group');
 const player=new WerewolfMusic({src:gameTracks[game],notify,storageKey:`orbis-${game}-bgm-enabled`});
 const control=player.button();control.classList.add('tabletop-music');
 return player;
}
