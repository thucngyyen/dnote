const fs=require('fs'),path=require('path'),ot=require('opentype.js');
const OUT=process.argv[2]; const F=n=>path.join(__dirname,'node_modules/@fontsource/inter/files/inter-latin-'+n+'-normal.woff');
const bold=ot.loadSync(F(700)), semi=ot.loadSync(F(600));
const C={ink:'#0A0C10',paper:'#F5F7FA',blue:'#2563EB',sky:'#3B82F6',slate:'#94A3B8'};
const BODY='M42 24h36l20 20v54a6 6 0 0 1-6 6H42a6 6 0 0 1-6-6V30a6 6 0 0 1 6-6z', FOLD='M78 24v14a6 6 0 0 0 6 6h14z';
const LINES={std:[[47,56,38,6,0],[47,70,38,6,0],[47,84,22,6,1]], small:[[46,56,40,10,0],[46,76,24,10,1]]};
const rects=(set,c0,c1)=>LINES[set].map(([x,y,w,h,a])=>`<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${h/2}" fill="${a?c1:c0}"/>`).join('');
const sheet=(o={})=>{const {body=C.paper,fold=C.sky,line=C.ink,accent=C.blue,set='std'}=o;return `<path d="${BODY}" fill="${body}"/><path d="${FOLD}" fill="${fold}"/>${rects(set,line,accent)}`};
const mono=(col,id,set='std')=>`<mask id="${id}" maskUnits="userSpaceOnUse" x="0" y="0" width="128" height="128"><rect width="128" height="128" fill="#fff"/>${rects(set,'#000','#000')}<path d="${FOLD}" fill="#000" stroke="#000" stroke-width="7" stroke-linejoin="round"/></mask><path d="${BODY}" fill="${col}" mask="url(#${id})"/><path d="${FOLD}" fill="${col}"/>`;
const svg=(vb,inner,w,h)=>`<svg xmlns="http://www.w3.org/2000/svg" viewBox="${vb}"${w?` width="${w}" height="${h}"`:''}>${inner}</svg>\n`;
const put=(f,s)=>{const p=path.join(OUT,f);fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,s)};
// text -> paths
function text(font,str,size,x,y,track=-0.03){const sc=size/font.unitsPerEm;let cx=x,out=[];const gl=font.stringToGlyphs(str);
 gl.forEach((g,i)=>{out.push(g.getPath(cx,y,size).toPathData(2));cx+=g.advanceWidth*sc+track*size;if(i<gl.length-1)cx+=font.getKerningValue(g,gl[i+1])*sc});
 return {d:out,w:cx-x-track*size}}
const capH=bold.tables.os2.sCapHeight/bold.unitsPerEm;
function word(cap,x,base,dCol,nCol){const t=text(bold,'DNote',cap/capH,x,base);return {svg:`<path d="${t.d[0]}" fill="${dCol}"/><path d="${t.d.slice(1).join('')}" fill="${nCol}"/>`,w:t.w}}
// ---- icons
const tile=(inner,rx=28)=>`<rect width="128" height="128" rx="${rx}" fill="${C.ink}"/>${inner}`;
put('svg/icon.svg',svg('0 0 128 128',tile(sheet())));
put('svg/icon-small.svg',svg('0 0 128 128',tile(sheet({set:'small'}))));
put('web/favicon.svg',svg('0 0 128 128',tile(sheet({set:'small'}))));
put('svg/icon-square.svg',svg('0 0 128 128',tile(sheet(),0)));
const SAFE='-16 -19 166 166';
put('svg/icon-maskable.svg',svg(SAFE,`<rect x="-16" y="-19" width="166" height="166" fill="${C.ink}"/>${sheet()}`));
put('svg/android-foreground.svg',svg(SAFE,sheet()));
put('svg/android-monochrome.svg',svg(SAFE,mono('#fff','m')));
put('svg/icon-round.svg',svg('-10 -13 154 154',`<circle cx="67" cy="64" r="77" fill="${C.ink}"/>${sheet()}`));
// macOS: 824 body on 1024 canvas w/ shadow
const k=824/128;
put('svg/icon-macos.svg',svg('0 0 1024 1024',`<defs><filter id="s" x="-20%" y="-20%" width="140%" height="140%"><feDropShadow dx="0" dy="10" stdDeviation="14" flood-color="#000" flood-opacity=".35"/></filter></defs><rect x="100" y="100" width="824" height="824" rx="185" fill="#000" filter="url(#s)"/><rect x="100" y="100" width="824" height="824" rx="185" fill="${C.ink}"/><g transform="translate(100 100) scale(${k})">${sheet()}</g>`));
// marks
const MVB='32 20 70 88';
put('svg/mark.svg',svg(MVB,sheet({body:C.ink,line:C.paper,accent:C.sky})));
put('svg/mark-reversed.svg',svg(MVB,sheet()));
put('svg/mark-mono-black.svg',svg(MVB,mono('#000','m')));
put('svg/mark-mono-white.svg',svg(MVB,mono('#fff','m')));
// wordmarks & lockups
for(const [mode,d,n,ms] of [['light',C.blue,C.ink,{body:C.ink,line:C.paper,accent:C.sky}],['dark',C.sky,C.paper,{}]]){
 const w=word(64,0,64,d,n); put(`svg/wordmark-${mode}.svg`,svg(`0 0 ${Math.ceil(w.w)} 64`,w.svg));
 const hw=word(44,88,63,d,n); put(`svg/lockup-horizontal-${mode}.svg`,svg(`0 0 ${Math.ceil(88+hw.w)} 80`,`<g transform="translate(-36 -24)">${sheet(ms)}</g>${hw.svg}`));
 const sw0=word(36,0,0,d,n).w, W=Math.ceil(Math.max(sw0,62)); const sw=word(36,(W-sw0)/2,140,d,n);
 put(`svg/lockup-stacked-${mode}.svg`,svg(`0 0 ${W} 140`,`<g transform="translate(${(W-62)/2-36} -24)">${sheet(ms)}</g>${sw.svg}`));
}
// og image
const ow=word(96,0,0,C.sky,C.paper).w, s2=2.2, lx=(1200-(88+ow*0+0))/2;
const lock=word(120,0,0,C.sky,C.paper); const total=62*s2+44+lock.w; const x0=(1200-total)/2;
const tag=text(semi,'Self-hosted notes, on every device.',34,0,0,-0.01);
const og=word(120,x0+62*s2+44,330,C.sky,C.paper);
const tg=text(semi,'Self-hosted notes, on every device.',34,(1200-tag.w)/2,438,-0.01);
put('web/og-image.svg',svg('0 0 1200 630',`<defs><radialGradient id="r" cx=".5" cy=".42" r=".6"><stop offset="0" stop-color="#1E3A8A" stop-opacity=".55"/><stop offset="1" stop-color="${C.ink}" stop-opacity="0"/></radialGradient></defs><rect width="1200" height="630" fill="${C.ink}"/><rect width="1200" height="630" fill="url(#r)"/><g transform="translate(${x0} 182) scale(${s2}) translate(-36 -24)">${sheet()}</g>${og.svg}<path d="${tg.d.join('')}" fill="${C.slate}"/>`,1200,630));
