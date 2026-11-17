package app.quiet.launcher.feature.player

import app.quiet.launcher.core.model.PlayerControl

/**
 * AGSL for the play orbs (API 33+). Each one is drawn over a rect around the orb; coordinates are
 * worked in orb radii (p = (xy - centre) / radius). Every program shares one header, so the same
 * uniforms can be set on all of them. Output is premultiplied.
 *
 * The glass drop follows the Jarvis presence "Glass" approach: a smooth-min metaball signed distance
 * field, a normal built from its gradient and a dome height profile, refraction of a coloured field
 * with per-channel offsets (chromatic dispersion), a fresnel rim, a tight specular and a rim line.
 */
object OrbShaders {

    val HEADER = """
uniform float2 uC;
uniform float uR;
uniform float uTime;
uniform float uFlow;
uniform float uPaused;
uniform float uBeat;
uniform float uPress;
uniform float2 uVel;
uniform float uRoll;
uniform float2 uT1;
uniform float2 uT2;
uniform float2 uM0;
uniform float2 uM1;
uniform float2 uM2;
uniform float2 uV0;
uniform float2 uV1;
uniform float2 uV2;
uniform float3 uDk;
uniform float3 uAc;
uniform float3 uMd;
uniform float3 uLt;
float hash21(float2 p){ p=fract(p*float2(123.34,456.21)); p+=dot(p,p+45.32); return fract(p.x*p.y); }
float vnoise(float2 p){ float2 i=floor(p); float2 f=fract(p); float2 u=f*f*(3.-2.*f);
  return mix(mix(hash21(i),hash21(i+float2(1.,0.)),u.x), mix(hash21(i+float2(0.,1.)),hash21(i+float2(1.,1.)),u.x), u.y); }
float fbm(float2 p){ float v=0.; float a=.5; for(int i=0;i<4;i++){ v+=a*vnoise(p); p=p*2.03+float2(1.7,9.2); a*=.5; } return v; }
float smin(float a,float b,float k){ float h=clamp(.5+.5*(b-a)/k,0.,1.); return mix(b,a,h)-k*h*(1.-h); }
float3 ramp(float t){ t=clamp(t,0.,1.);
  if(t<.33) return mix(uDk,uMd,t/.33);
  if(t<.66) return mix(uMd,uAc,(t-.33)/.33);
  return mix(uAc,uLt,(t-.66)/.34); }
float lum(float3 c){ return dot(c,float3(.299,.587,.114)); }
// Play mark (a rounded triangle), in orb radii, a little right of centre.
float playMark(float2 p){
  float2 q=p-float2(.06,0.);
  float k=sqrt(3.);
  q=float2(-q.y, q.x)*3.;
  q.x=abs(q.x)-1.; q.y=q.y+1./k;
  if(q.x+k*q.y>0.) q=float2(q.x-k*q.y,-k*q.x-q.y)/2.;
  q.x-=clamp(q.x,-2.,0.);
  return (-length(q)*sign(q.y))/3.-.02;
}
// The body shared by the two drops: a squashed circle stretched along the velocity, merged with two
// trailing droplets that lag behind on their own springs.
float dropShape(float2 p, float wob){
  float vl=length(uVel);
  float sp=clamp(vl/80.,0.,.38);
  float2 dir=vl>1e-3? uVel/vl : float2(1.,0.);
  float2 q=float2(dot(p,dir), dot(p,float2(-dir.y,dir.x)));
  q.x/=1.+sp; q.y/=1.-sp*.55;
  float r=(1.+uBeat*.06)*uPress*(1.-uPaused*.08);
  float a=atan(p.y,p.x);
  float body=length(q)-r-wob*((1.-uPaused)*(.035*sin(3.*a+uTime*2.3)+.025*sin(5.*a-uTime*3.1))+uBeat*.04*sin(2.*a+uTime));
  float t1=length(p-(uT1-uC)/uR)-.46*r;
  float t2=length(p-(uT2-uC)/uR)-.26*r;
  return smin(smin(body,t1,.5),t2,.4);
}
float2 dropNormalXY(float2 p, float wob){
  float2 e=float2(.01,0.);
  float2 g=float2(dropShape(p+e.xy,wob)-dropShape(p-e.xy,wob), dropShape(p+e.yx,wob)-dropShape(p-e.yx,wob));
  return g/(length(g)+1e-5);
}
"""

    /** Liquid ink: a swirl of the cover's colours flowing inside a glossy drop. */
    val DROP = """
half4 main(float2 xy){
  float2 p=(xy-uC)/uR;
  float d=dropShape(p,1.);
  float px=1.6/uR;
  float inside=1.-smoothstep(-px,px,d);
  float2 nd=dropNormalXY(p,1.);
  float s=clamp(-d/.55,0.,1.);
  float h=sqrt(1.-(1.-s)*(1.-s));
  float3 n=normalize(float3(nd*(1.-h),h+.05));
  // Ink currents: domain-warped noise, dragged along by the motion.
  float2 q=p*1.35+n.xy*.35-uVel*.004;
  float2 w=float2(fbm(q+float2(uFlow*.35,-uFlow*.2)), fbm(q+float2(-uFlow*.25,uFlow*.3)+4.1));
  float f=fbm(q*1.2+w*2.2+float2(0.,uFlow*.15));
  float3 c=ramp(.15+f*1.05);
  c=mix(c,uLt,smoothstep(.62,.9,f)*.35);
  c*=.42+.7*h;
  c+=pow(1.-n.z,2.6)*mix(uAc,uLt,.5)*.7;
  float3 L=normalize(float3(-.45,-.6,.75));
  c+=pow(max(dot(reflect(-L,n),float3(0.,0.,1.)),0.),36.)*.9;
  c+=pow(max(dot(reflect(-normalize(float3(.5,.4,.8)),n),float3(0.,0.,1.)),0.),12.)*.12*uLt;
  float pm=(1.-smoothstep(-px,px,playMark(p)))*uPaused;
  c=mix(c,uDk*.6,pm*.85);
  float glow=exp(-max(d,0.)*4.5)*(1.-inside)*(.22+.25*uBeat)*(1.-uPaused*.5);
  float3 o=uAc*glow;
  float a=max(inside,lum(o)*1.4);
  return half4(c*inside+o, a);
}
"""

    /** Glass: a clear drop that bends a slow field of the cover's colours behind it. */
    val GLASS = """
float3 field(float2 p){
  float t=uFlow*.55;
  float2 a=float2(sin(t*1.1),cos(t*.9))*.55;
  float2 b=float2(cos(t*.7+2.),sin(t*1.3+1.))*.55;
  float2 c=float2(sin(t*.5+4.),cos(t*.8+3.))*.55;
  float wa=exp(-dot(p-a,p-a)*2.2); float wb=exp(-dot(p-b,p-b)*2.2); float wc=exp(-dot(p-c,p-c)*2.2);
  float3 col=uAc*wa+uLt*wb*.8+uMd*wc+uDk*.6+float3(.02,.022,.03);
  return col*(.92+.08*sin((p.x+p.y)*9.+t*2.));
}
half4 main(float2 xy){
  float2 p=(xy-uC)/uR;
  float d=dropShape(p,.55);
  float px=1.6/uR;
  float inside=1.-smoothstep(-px,px,d);
  float2 nd=dropNormalXY(p,.55);
  float s=clamp(-d/.62,0.,1.);
  float h=sqrt(1.-(1.-s)*(1.-s));
  float3 n=normalize(float3(nd*(1.-h),h+.04));
  float2 off=-n.xy*.85;
  float3 col=float3(field(p+off).r, field(p+off*1.1).g, field(p+off*1.22).b)*1.1;
  col*=1.-uPaused*.35;
  col+=pow(1.-n.z,3.)*mix(float3(1.),uLt,.4)*.6;
  float3 H=normalize(float3(-.45,-.6,.65)+float3(0.,0.,1.));
  col+=pow(max(dot(n,H),0.),90.)*1.5;
  col+=pow(max(dot(n,normalize(float3(.5,.55,.7)+float3(0.,0.,1.))),0.),30.)*.18;
  float rim=1.-smoothstep(0.,.03,abs(d+.04));
  col+=rim*.22*uLt;
  float pm=(1.-smoothstep(-px,px,playMark(p)))*uPaused;
  col=mix(col,float3(.95,.94,.92),pm*.8);
  float glow=exp(-max(d,0.)*6.)*(1.-inside)*(.12+.2*uBeat);
  float3 o=uLt*glow*.7;
  return half4(col*inside*.95+o, max(inside*.95,lum(o)*1.3));
}
"""

    /** Marble: veined colour turning inside a glass sphere, with a contact shadow. */
    val MARBLE = """
half4 main(float2 xy){
  float2 p=(xy-uC)/uR;
  float r=uPress*(1.+uBeat*.03);
  float2 b=p/r;
  float rr=dot(b,b);
  float px=1.6/(uR*r);
  float inside=1.-smoothstep(1.-px,1.+px,sqrt(rr));
  // Contact shadow on the "floor" below.
  float2 sp=(p-float2(0.,1.16*r))/float2(1.05*r*(1.+(1.-uPress)*2.),.2*r);
  float shadow=exp(-dot(sp,sp)*1.4)*.6*(1.-inside);
  float z=sqrt(max(0.,1.-rr));
  float3 P=float3(b,z);
  float3 n=P;
  // Look into the glass: the refracted ray reaches deeper, so the veins sit inside, not on the skin.
  float3 R=normalize(float3(b*.72,z));
  float ca=cos(uRoll), sa=sin(uRoll);
  float3 Q=float3(ca*R.x+sa*R.z, R.y, -sa*R.x+ca*R.z);
  float2 uv=float2(atan(Q.x,Q.z)*1.1, Q.y*2.2);
  float2 w=float2(fbm(uv*1.3+float2(uFlow*.05,0.)), fbm(uv*1.3+5.2));
  float f=fbm(uv*1.1+w*2.6);
  float vein=pow(.5+.5*sin(f*14.+uv.y*3.),6.);
  float3 c=ramp(.1+f*.9);
  c=mix(c,uLt,vein*.55);
  c=mix(c,uDk,smoothstep(.2,.0,z)*.5);
  c*=.38+.72*z;
  c*=1.-uPaused*.4;
  float fres=pow(1.-z,3.);
  c+=fres*mix(float3(1.),uLt,.5)*.55;
  float3 L=normalize(float3(-.42,-.55,.72));
  c+=pow(max(dot(reflect(-L,n),float3(0.,0.,1.)),0.),70.)*1.3;
  // A soft window reflection across the top.
  float win=smoothstep(.2,.0,abs(b.y+.48+.12*b.x*b.x))*smoothstep(.65,.2,abs(b.x))*.18;
  c+=win;
  float pm=(1.-smoothstep(-px,px,playMark(b)))*uPaused;
  c=mix(c,float3(.93,.92,.9),pm*.85);
  float rim=(1.-smoothstep(0.,.05,abs(sqrt(rr)-.985)))*inside*.25;
  c+=rim;
  return half4(c*inside, max(inside, shadow));
}
"""

    /** Little sun: a churning plasma core with a corona, and three moons with comet tails. */
    val SUN = """
float2 seg(float2 p,float2 a,float2 b){ float2 pa=p-a, ba=b-a; float t=clamp(dot(pa,ba)/max(dot(ba,ba),1e-5),0.,1.); return float2(length(pa-ba*t), t); }
float3 moon(float2 p,float2 m,float2 v,float3 col,float rad){
  float2 mm=(m-uC)/uR;
  float2 tail=mm-(v/uR)*.06;
  float2 st=seg(p,mm,tail);
  float dTrail=st.x; float t=st.y;
  float trail=exp(-dTrail*dTrail/(rad*rad*1.1))*(1.-t)*.75;
  float dd=length(p-mm);
  float body=1.-smoothstep(rad-.03,rad+.03,dd);
  float halo=exp(-dd*dd/(rad*rad*6.))*.35;
  return col*(body+trail+halo);
}
half4 main(float2 xy){
  float2 p=(xy-uC)/uR;
  float r=length(p);
  float ang=atan(p.y,p.x);
  float rc=.5*uPress*(1.+uBeat*.12);
  float coreVis=1.-uPaused*.85;
  float px=1.6/uR;
  float inside=(1.-smoothstep(rc-px,rc+px,r));
  float2 b=p/rc;
  float z=sqrt(max(0.,1.-dot(b,b)));
  float2 q=b*1.6/(.5+.5*z);
  float f=fbm(q*1.6+float2(uFlow*.6,-uFlow*.4)+fbm(q*2.+uFlow*.3)*1.5);
  float3 core=mix(uAc,uLt,smoothstep(.3,.8,f));
  core=mix(core,float3(1.,.98,.94),smoothstep(.65,1.,f)*.6);
  core*=.55+.6*z;
  core+=pow(1.-z,2.)*uLt*.4;
  // Corona: rays of noise in angle, breathing with the music.
  float ray=fbm(float2(ang*2.2+uFlow*.15, r*2.4-uFlow*.9));
  float cor=exp(-max(r-rc,0.)*(4.2-uBeat*1.2))*(.35+ray*.9)*(1.-inside)*coreVis;
  float3 col=core*inside*coreVis+uAc*cor*.8+uLt*cor*cor*.25;
  float mr=.13+uPaused*.04;
  col+=moon(p,uM0,uV0,uLt,mr);
  col+=moon(p,uM1,uV1,uAc,mr);
  col+=moon(p,uM2,uV2,mix(uMd,uLt,.4),mr);
  float a=clamp(max(inside*coreVis, lum(col)*1.5),0.,1.);
  col=min(col,float3(a));
  return half4(col, a);
}
"""

    fun source(style: PlayerControl): String = HEADER + when (style) {
        PlayerControl.MARBLE -> MARBLE
        PlayerControl.SUN -> SUN
        PlayerControl.GLASS -> GLASS
        else -> DROP
    }

    val FLOAT1 = listOf("uR", "uTime", "uFlow", "uPaused", "uBeat", "uPress", "uRoll")
    val FLOAT2 = listOf("uC", "uVel", "uT1", "uT2", "uM0", "uM1", "uM2", "uV0", "uV1", "uV2")
    val FLOAT3 = listOf("uDk", "uAc", "uMd", "uLt")
}
