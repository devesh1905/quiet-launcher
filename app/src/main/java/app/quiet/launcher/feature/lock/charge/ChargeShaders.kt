package app.quiet.launcher.feature.lock.charge

/**
 * AGSL for the charging animations (API 33+). Flood, Filling glass and Clock fill run as a
 * RenderEffect over the live lock scene (`content`), so refraction bends the real painting, cover and
 * clock under them; Light thread is the paint shader of a triangle-strip ribbon (xy = ribbon coords).
 *
 * The glass look is the play orb's Glass drop ([app.quiet.launcher.feature.player.OrbShaders.GLASS]):
 * a dome normal from the signed distance, refraction with per-channel offsets (chromatic dispersion),
 * a fresnel rim, a tight specular and a rim line. Small textures (`uSurf`, `uHF`) are RGBA_F16
 * bitmaps written by the simulations, biased around 0.5 and read with bilinear filtering.
 */
internal object ChargeShaders {

    private val PALETTE = """
uniform float3 uDk;
uniform float3 uAc;
uniform float3 uMd;
uniform float3 uLt;
uniform float3 uSheen;
float hash21(float2 p){ p=fract(p*float2(123.34,456.21)); p+=dot(p,p+45.32); return fract(p.x*p.y); }
float lum(float3 c){ return dot(c,float3(.299,.587,.114)); }
"""

    // ------------------------------------------------------------------ Flood
    val FLOOD = """
uniform shader content;
uniform shader uHF;
uniform shader uSurf;
uniform float2 uRes;
uniform float2 uGrid;
uniform float uSurfN;
uniform float uSurfAmp;
uniform float uWater;
uniform float2 uPort;
uniform float4 uJet;
uniform float uJetOn;
uniform float uFlow;
uniform float uSwirl;
uniform float uTime;
uniform float uDp;
uniform float uRefr;
uniform float uCaus;
uniform float uBubOn;
uniform float uDrops;
uniform float3 uBub[18];
uniform float3 uDrop[12];
uniform float4 uCover;
uniform float3 uBob;
$PALETTE
half4 src(float2 p){ return content.eval(clamp(p,float2(.5),uRes-.5)); }
float3 src3(float2 p,float2 o){ return float3(src(p+o).r, src(p+o*1.07).g, src(p+o*1.15).b); }
float surfY(float x){
  float u=clamp(x/uRes.x,0.,1.)*(uSurfN-1.)+.5;
  return uWater-(uSurf.eval(float2(u,.5)).r-.5)*uSurfAmp;
}
// A slow swell under the ripples (gradient xy and laplacian z, in grid cells), so the caustic net
// never stops moving while the water is lit.
float3 swell(float2 q){
  float3 r=float3(0.);
  float2 d1=float2(.8,.6); float k1=.449; float s1=.045; float p1=dot(d1,q)*k1-uTime*1.3;
  r+=float3(d1*s1*cos(p1), -s1*k1*sin(p1));
  float2 d2=float2(-.62,.78); float k2=.698; float s2=.03; float p2=dot(d2,q)*k2-uTime*1.75+1.7;
  r+=float3(d2*s2*cos(p2), -s2*k2*sin(p2));
  float2 d3=float2(.12,-.99); float k3=.3; float s3=.035; float p3=dot(d3,q)*k3-uTime*.9+4.;
  r+=float3(d3*s3*cos(p3), -s3*k3*sin(p3));
  float2 d4=float2(-.95,-.3); float k4=1.05; float s4=.018; float p4=dot(d4,q)*k4-uTime*2.3+2.2;
  r+=float3(d4*s4*cos(p4), -s4*k4*sin(p4));
  return r;
}
float sdBox(float2 p,float2 b){ float2 q=abs(p)-b; return length(max(q,0.))+min(max(q.x,q.y),0.); }
half4 main(float2 xy){
  float sy=surfY(xy.x);
  float d=xy.y-sy;
  float3 H=normalize(float3(-.35,-.75,.55)+float3(0.,0.,1.));
  // The floating cover: it rides the local wave, its rim bends the water around it.
  float2 sp=xy;
  float coverRim=0.;
  float2 coverOff=float2(0.);
  if(uBob.z>0.){
    float2 cc=(uCover.xy+uCover.zw)*.5; float2 hs=(uCover.zw-uCover.xy)*.5;
    float sd=sdBox(xy-cc, hs+abs(uBob.xy)+2.);
    float m=1.-smoothstep(0.,12.*uDp,sd);
    sp=xy-uBob.xy*m*uBob.z;
    float2 c2=cc+uBob.xy*uBob.z;
    float sc=sdBox(xy-c2,hs);
    float e=1.;
    float2 gc=float2(sdBox(xy+float2(e,0.)-c2,hs)-sdBox(xy-float2(e,0.)-c2,hs), sdBox(xy+float2(0.,e)-c2,hs)-sdBox(xy-float2(0.,e)-c2,hs))*.5;
    float ring=exp(-sc*sc/(2.*16.*uDp*uDp));
    coverOff=gc*ring*7.*uDp*uBob.z;
    coverRim=exp(-sc*sc/(2.*uDp*uDp))*uBob.z;
  }
  float3 col;
  if(d<-30.*uDp && uJetOn<=0. && uDrops<=0. && uBob.z<=0.){ return src(xy); }
  half4 base=src(sp);
  col=base.rgb;
  float a=smoothstep(-1.,1.,d);
  if(d>-2.){
    float near=exp(-max(d,0.)/(uRes.y*.32));
    float cell=uRes.x/uGrid.x;
    float4 t=uHF.eval(xy/uRes*uGrid);
    float3 g=float3((t.r-.5)*2., (t.g-.5)*2., (t.b-.5)*2.);
    g+=swell(xy/cell)*(.4+.6*near);
    // The stream from the port: a shimmering column, stronger on fast charge.
    float colm=exp(-abs(xy.x-uPort.x)/(16.*uDp))*uFlow*smoothstep(0.,80.*uDp,d);
    g.x+=colm*.16*sin(xy.y*.05-uTime*11.)*sin(xy.y*.013+uTime*3.);
    // Draining: the water turns around the port on its way out.
    float2 rel=sp-uPort;
    float rl=length(rel);
    float ang=uSwirl*exp(-rl/(110.*uDp))*2.4;
    float ca=cos(ang); float sa=sin(ang);
    float2 wp=uPort+float2(ca*rel.x-sa*rel.y, sa*rel.x+ca*rel.y);
    float2 off=-g.xy*uRefr+coverOff;
    // Just under the meniscus the image lifts (the surface acts as a lens).
    off.y-=9.*uDp*exp(-max(d,0.)/(9.*uDp));
    float3 w=src3(wp,off);
    // Absorption with depth: red goes first; a little of the painting's own dark colour scatters back.
    float dz=max(d,0.)/uRes.y;
    float3 ab=exp(-dz*float3(2.1,1.05,.7));
    float3 water=mix(uDk,uMd,.45);
    w=w*mix(float3(1.),ab,.8)+water*(1.-ab.g)*.42;
    // Caustics: light focuses where the surface curves (negative laplacian), brightest near the top.
    float c=max(-g.z*uCaus,0.);
    c=c*c/(1.+c*.6);
    w+=mix(uSheen,uLt,.4)*c*(.42*near+.04);
    // Glints off the ripples, and the bright band of light just under the surface.
    float3 n=normalize(float3(-g.xy*2.4,1.));
    w+=uSheen*pow(max(dot(n,H),0.),300.)*1.3*(.25+.75*near);
    w+=uLt*.07*exp(-max(d,0.)/(22.*uDp));
    col=mix(col,w,a);
    // The meniscus: a thin bright line and a faint glow over it.
    float men=exp(-d*d/(2.*1.15*uDp*1.15*uDp));
    col+=uSheen*men*.75+uSheen*.09*exp(-abs(d)/(6.*uDp))*(1.-a);
  }
  col+=uSheen*coverRim*.28;
  // Bubbles rising from the port.
  if(uBubOn>0.){
    for(int i=0;i<18;i++){
      float3 b=uBub[i];
      if(b.z>0.){
        float e=length(xy-b.xy)-b.z;
        if(e<4.*uDp){
          float ring=exp(-e*e/(.45*uDp*uDp));
          float inner=1.-smoothstep(-1.,1.,e);
          float2 hp=b.xy+float2(-.35,-.4)*b.z;
          float dot1=exp(-dot(xy-hp,xy-hp)/(.08*b.z*b.z+.3));
          col+=(uSheen*ring*.55+inner*.05+dot1*.55)*a;
        }
      }
    }
  }
  // Splash drops: tiny glass lenses.
  if(uDrops>0.){
    for(int i=0;i<12;i++){
      float3 dr=uDrop[i];
      if(dr.z>0.){
        float2 v=(xy-dr.xy)/dr.z;
        float rr=dot(v,v);
        if(rr<1.2){
          float z=sqrt(max(0.,1.-rr));
          float3 bn=float3(v*(1.-z),z+.04);
          bn=normalize(bn);
          float3 rc=src3(xy,-v*dr.z*.95);
          rc+=pow(1.-z,3.)*uSheen*.55;
          rc+=pow(max(dot(bn,H),0.),70.)*1.2;
          col=mix(col,rc,1.-smoothstep(.85,1.05,rr));
        }
      }
    }
  }
  // The jet out of the port: a refracting column, rounded at its tip, wobbling as it pours.
  if(uJetOn>0.){
    float tip=uJet.y; float tail=uJet.z; float hw0=uJet.w;
    float yy=clamp(xy.y,tip+hw0*.5,tail);
    float k=clamp((xy.y-tip)/max(tail-tip,1.),0.,1.);
    float hw=hw0*(.5+.5*sqrt(k))*(1.+.1*sin(xy.y*.045-uTime*26.));
    float wx=uJet.x+sin(xy.y*.011+uTime*3.)*3.*uDp;
    float2 dv=float2(xy.x-wx, xy.y-yy);
    float dj=length(dv)-hw;
    if(dj<2.){
      float2 nn=clamp(dv/hw,-1.,1.);
      float rr=min(dot(nn,nn),1.);
      float z=sqrt(1.-rr);
      float3 jn=normalize(float3(nn*(1.-z),z+.05));
      float3 jc=src3(xy,float2(-nn.x*hw*1.5, -nn.y*hw-16.*uDp));
      jc=jc*float3(.9,.95,1.)+mix(uDk,uMd,.45)*.15;
      jc+=pow(1.-z,2.5)*uSheen*.75;
      jc+=pow(max(dot(jn,H),0.),40.)*.9;
      jc+=uSheen*.14*smoothstep(.55,1.,sin(xy.y*.08+uTime*44.+nn.x*3.));
      col=mix(col,jc,(1.-smoothstep(-1.,1.,dj))*uJetOn);
    }
  }
  return half4(col,1.);
}
"""

    // ------------------------------------------------------------------ Filling glass
    val GLASS = """
uniform shader content;
uniform shader uSurf;
uniform float2 uRes;
uniform float2 uC;
uniform float uR;
uniform float2 uSq;
uniform float uFillY;
uniform float uSurfN;
uniform float uSurfAmp;
uniform float uLiquid;
uniform float uSpark;
uniform float uMode;
uniform float uTime;
uniform float uDp;
uniform float3 uBub[16];
$PALETTE
half4 src(float2 p){ return content.eval(clamp(p,float2(.5),uRes-.5)); }
float3 src3(float2 p,float2 o){ return float3(src(p+o).r, src(p+o*1.1).g, src(p+o*1.22).b); }
float surfAt(float x){ float u=clamp(x*.5+.5,0.,1.)*(uSurfN-1.)+.5; return (uSurf.eval(float2(u,.5)).r-.5)*uSurfAmp; }
half4 main(float2 xy){
  float2 p=(xy-uC)/uR;
  // ---- inside the play orb (music): only a liquid layer, so every orb style keeps its own look.
  if(uMode>.5){
    float rr=length(p);
    float rad=.84;
    if(rr>rad+.12){ return src(xy); }
    half4 base=src(xy);
    float px=1.5/uR;
    float inC=1.-smoothstep(rad-px,rad+px,rr);
    float sy=uFillY-surfAt(p.x/rad);
    float dl=p.y-sy;
    float liq=smoothstep(-px,px,dl)*inC*uLiquid;
    float dep=clamp(dl/max(rad-sy,.05),0.,1.);
    float3 tint=mix(uLt,uAc,dep);
    float3 col=mix(base.rgb, base.rgb*.45+tint*.75, liq*.6);
    col+=uSheen*exp(-dl*dl/(2.*.022*.022))*inC*.85*uLiquid;
    return half4(col,1.);
  }
  float2 b=p/uSq;
  float r=length(b);
  if(r>1.9){ return src(xy); }
  float px=1.5/(uR*min(uSq.x,uSq.y));
  float d=r-1.;
  float inside=1.-smoothstep(-px,px,d);
  float3 col=src(xy).rgb;
  // Light focused through the drop pools on the "floor" under it; a soft contact shadow round it.
  float2 cq=(p-float2(0.,1.16*uSq.y))/float2(.9*uSq.x,.15*uSq.y+.02);
  float pool=exp(-dot(cq,cq)*1.5)*smoothstep(.0,.3,uSq.y);
  col=col*(1.-pool*.3)+mix(uAc,uLt,.6)*pool*.32*uLiquid;
  if(inside<=0.){ return half4(col,1.); }
  float s=clamp(-d/.62,0.,1.);
  float h=sqrt(1.-(1.-s)*(1.-s));
  float2 nd=r>1e-4? b/r : float2(0.);
  float3 n=normalize(float3(nd*(1.-h),h+.04));
  float2 off=-n.xy*.85*uR;
  float3 g=src3(xy,off)*1.04;
  // ---- the liquid
  float sy=uFillY-surfAt(b.x);
  sy-=.05*pow(abs(b.x),6.);
  float dl=b.y-sy;
  float liq=smoothstep(-px*1.2,px*1.2,dl)*uLiquid;
  if(liq>0.){
    float3 lc=src3(xy,off*1.35+float2(0.,-6.*uDp));
    float dep=clamp(dl/max(1.-sy,.05),0.,1.);
    float3 tint=mix(uLt,uAc,clamp(dep*.9+.1,0.,1.));
    lc=lc*mix(float3(1.),tint*1.25,.6)+tint*.2*(1.-dep*.5);
    // Light coming down through the surface: a few soft shafts.
    lc+=uSheen*.06*smoothstep(.4,1.,sin(b.x*7.+uTime*.7+b.y*2.))*(1.-dep);
    for(int i=0;i<16;i++){
      float3 bb=uBub[i];
      if(bb.z>0.){
        float e=length(b-bb.xy)-bb.z;
        lc+=uSheen*exp(-e*e/(bb.z*bb.z*.09+1e-5))*.55;
        float2 hp=bb.xy+float2(-.4,-.45)*bb.z;
        lc+=exp(-dot(b-hp,b-hp)/(bb.z*bb.z*.04+1e-6))*.45;
      }
    }
    if(uSpark>0.){
      float2 gq=b*13.+float2(0.,uTime*.05);
      float2 id=floor(gq);
      float hs=hash21(id);
      float2 fq=fract(gq)-.5-float2(hash21(id+3.1)-.5,hash21(id+7.7)-.5)*.5;
      float tw=pow(max(0.,sin(uTime*(1.7+hs*2.6)+hs*40.)),14.);
      float star=exp(-dot(fq,fq)*90.)+exp(-abs(fq.x)*60.-abs(fq.y)*9.)*.5+exp(-abs(fq.y)*60.-abs(fq.x)*9.)*.5;
      lc+=uSheen*tw*star*step(.7,hs)*uSpark*1.5;
    }
    g=mix(g,lc,liq);
  }
  g+=uSheen*exp(-dl*dl/(2.*.011*.011))*.85*uLiquid*inside;
  // ---- the glass (as the orb's Glass drop)
  g+=pow(1.-n.z,3.)*mix(float3(1.),uLt,.4)*.6;
  float3 Hh=normalize(float3(-.45,-.6,.65)+float3(0.,0.,1.));
  g+=pow(max(dot(n,Hh),0.),90.)*1.5;
  g+=pow(max(dot(n,normalize(float3(.5,.55,.7)+float3(0.,0.,1.))),0.),30.)*.18;
  float rim=1.-smoothstep(0.,.03,abs(d+.04));
  g+=rim*.22*uLt;
  col=mix(col,g,inside);
  return half4(col,1.);
}
"""

    // ------------------------------------------------------------------ Light thread (ribbon paint)
    val THREAD = """
uniform float uLen;
uniform float uHalfW;
uniform float uLevel;
uniform float uFade;
uniform float uSpark;
uniform float uDrawing;
uniform float uTime;
uniform float uDp;
uniform float uPulse[6];
$PALETTE
half4 main(float2 xy){
  float s=xy.x;
  float ax=abs(xy.y)*uHalfW;
  float edge=1.-xy.y*xy.y;
  float core=exp(-ax*ax/(2.*.75*uDp*.75*uDp));
  float halo=(exp(-ax/(4.5*uDp))*.32+exp(-ax*ax/(2.*2.4*uDp*2.4*uDp))*.45)*edge;
  float lit=smoothstep(uLevel+.012,uLevel-.012,s);
  float bright=mix(.16,1.,lit);
  // A faint shimmer running up the lit part, so it reads as current, not as a line.
  bright*=1.+.08*sin(s*uLen*.05-uTime*6.)*lit;
  float p=0.;
  for(int i=0;i<6;i++){
    float q=uPulse[i];
    if(q>=0.){ float dd=(s-q)*uLen; p+=exp(-dd*dd/(2.*18.*uDp*18.*uDp))*mix(.35,1.,smoothstep(uLevel+.05,uLevel-.05,q)); }
  }
  float3 glow=mix(uLt,uSheen,.35);
  float3 c=glow*halo*bright*(1.+p*1.8)+mix(glow,float3(1.),.65)*core*(bright*.85+p*.9);
  if(uDrawing>0.){
    float dd=(s-uSpark)*uLen;
    float head=exp(-dd*dd/(2.*10.*uDp*10.*uDp));
    c+=float3(1.)*core*head*2.+glow*halo*head*2.5;
    c*=smoothstep(uSpark+.004,uSpark-.004,s)+head;
  }
  c*=uFade;
  float al=clamp(max(c.r,max(c.g,c.b)),0.,1.);
  return half4(min(c,float3(al)),al);
}
"""

    // ------------------------------------------------------------------ Clock fill
    val CLOCK = """
uniform shader content;
uniform shader uSurf;
uniform float2 uSize;
uniform float uSurfN;
uniform float uSurfAmp;
uniform float uFillY;
uniform float uCap;
uniform float uPresence;
uniform float uShine;
uniform float uTime;
uniform float uDp;
$PALETTE
float m(float2 p){ return content.eval(p).a; }
half4 main(float2 xy){
  half4 c0=content.eval(xy);
  float a=c0.a;
  if(a<.003){ return c0; }
  float3 ink=c0.rgb/a;
  // A bevel from the glyph mask: its gradient over a few px gives the glass edge normal.
  float e=2.2*uDp;
  float2 gr=float2(m(xy+float2(e,0.))-m(xy-float2(e,0.)), m(xy+float2(0.,e))-m(xy-float2(0.,e)));
  float e2=e*.55;
  gr+=float2(m(xy+float2(e2,e2))-m(xy-float2(e2,e2)), m(xy+float2(-e2,e2))-m(xy-float2(-e2,e2)))*float2(.7,.7);
  float3 n=normalize(float3(-gr*1.3,1.));
  float u=clamp(xy.x/uSize.x,0.,1.)*(uSurfN-1.)+.5;
  float sy=uFillY-(uSurf.eval(float2(u,.5)).r-.5)*uSurfAmp;
  float dl=xy.y-sy;
  float liq=smoothstep(-.8,.8,dl);
  float dep=clamp(dl/max(uCap,1.),0.,1.);
  // Liquid light: bright at the top of the liquid, deepening to the accent, with slow inner currents.
  float3 lq=mix(mix(uLt,uSheen,.4),uAc,dep*.85);
  lq*=.9+.12*sin(xy.x*.035+xy.y*.02-uTime*1.4)+.08*sin(xy.x*.09-uTime*2.3);
  lq+=pow(1.-n.z,1.5)*uSheen*.35;
  // Empty glass: the ink thinned, its edges lit.
  float3 gl=ink*.22+float3(.02);
  float3 col=mix(gl,lq,liq);
  col+=uSheen*exp(-dl*dl/(2.*1.1*uDp*1.1*uDp))*.9;
  float3 L=normalize(float3(-.5,-.75,.6));
  float3 Hh=normalize(L+float3(0.,0.,1.));
  col+=pow(max(dot(n,Hh),0.),24.)*.55*mix(float3(1.),uSheen,.5);
  col+=pow(1.-n.z,2.)*mix(uLt,float3(1.),.5)*.45;
  if(uShine>-.5){
    float sx=xy.x/uSize.x-(1.-xy.y/uSize.y)*.25;
    float band=exp(-(sx-uShine)*(sx-uShine)*90.);
    col+=float3(1.)*band*.7;
  }
  col=mix(ink,col,uPresence);
  return half4(col*a,a);
}
"""

    fun flood() = FLOOD.replace("\$PALETTE", PALETTE)
    fun glass() = GLASS.replace("\$PALETTE", PALETTE)
    fun thread() = THREAD.replace("\$PALETTE", PALETTE)
    fun clock() = CLOCK.replace("\$PALETTE", PALETTE)
}
