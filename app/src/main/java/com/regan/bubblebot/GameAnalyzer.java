package com.regan.bubblebot;

import android.graphics.Bitmap;
import android.os.SystemClock;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Fast local vision + geometry player tuned for the 588x1280 portrait bubble game in the supplied video. */
public final class GameAnalyzer {
    private static final int CW = 588, CH = 1280;
    private static final double R = 25.0;
    private static final double DX = 51.0, DY = 44.0, DIAGX = 25.5;
    private static long lastShot = 0;
    private static final Object LOCK = new Object();

    enum Color { RED, YELLOW, GREEN, CYAN, PURPLE, PINK, UNKNOWN }
    static final class Ball { double x,y,r; Color c; Ball(double x,double y,double r,Color c){this.x=x;this.y=y;this.r=r;this.c=c;} }
    static final class Candidate { double x,y,angle,score; boolean bounce; Candidate(double x,double y,double angle,double score,boolean bounce){this.x=x;this.y=y;this.angle=angle;this.score=score;this.bounce=bounce;} }

    public static void process(Bitmap original, int realW, int realH) {
        synchronized (LOCK) {
            if (!GameAccessibilityService.isReady()) return;
            if (SystemClock.uptimeMillis() - lastShot < 1200) return;
            Bitmap small = original;
            if (realW != CW || realH != CH) {
                small = Bitmap.createScaledBitmap(original, CW, CH, false);
            }
            Mat src = new Mat();
            Utils.bitmapToMat(small, src);
            List<Ball> balls = detectBalls(src);
            Color current = sampleColor(src, 294, 1065, 18);
            if (small != original) small.recycle();
            src.release();
            if (current == Color.UNKNOWN || balls.size() < 3) return;
            Candidate best = chooseShot(balls, current);
            if (best == null) return;
            fire(best.angle, realW, realH);
        }
    }

    private static List<Ball> detectBalls(Mat src) {
        Mat roi = new Mat(src, new org.opencv.core.Rect(0, 175, CW, 360));
        Mat gray = new Mat();
        Imgproc.cvtColor(roi, gray, Imgproc.COLOR_RGBA2GRAY);
        Imgproc.GaussianBlur(gray, gray, new Size(5,5), 1.2);
        Mat circles = new Mat();
        Imgproc.HoughCircles(gray, circles, Imgproc.HOUGH_GRADIENT, 1.2, 32, 100, 22, 17, 31);
        List<Ball> out = new ArrayList<>();
        for (int i=0;i<circles.cols();i++) {
            double[] d = circles.get(0,i); if (d == null) continue;
            double x=d[0], y=d[1]+175, r=d[2];
            if (y < 178 || y > 515) continue;
            Color c = sampleColor(src, x, y, 12);
            if (c == Color.UNKNOWN) continue;
            boolean duplicate=false;
            for (Ball b:out) if (Math.hypot(b.x-x,b.y-y)<18) { duplicate=true; break; }
            if (!duplicate) out.add(new Ball(x,y,r,c));
        }
        circles.release(); gray.release(); roi.release();
        return out;
    }

    private static Color sampleColor(Mat rgba, double cx, double cy, int rad) {
        int x=(int)Math.round(cx), y=(int)Math.round(cy);
        int x0=Math.max(0,x-rad), y0=Math.max(0,y-rad), x1=Math.min(CW-1,x+rad), y1=Math.min(CH-1,y+rad);
        if (x1<=x0 || y1<=y0) return Color.UNKNOWN;
        Mat patch = rgba.submat(y0,y1+1,x0,x1+1);
        Mat hsv = new Mat(); Imgproc.cvtColor(patch,hsv,Imgproc.COLOR_RGBA2HSV);
        ArrayList<Double> hs=new ArrayList<>();
        for(int yy=0;yy<hsv.rows();yy++) for(int xx=0;xx<hsv.cols();xx++) {
            double[] p=hsv.get(yy,xx); if(p!=null && p[1]>95 && p[2]>80) hs.add(p[0]);
        }
        patch.release(); hsv.release();
        if(hs.size()<8) return Color.UNKNOWN;
        Collections.sort(hs); double h=hs.get(hs.size()/2);
        if(h<10 || h>=172) return Color.RED;
        if(h<40) return Color.YELLOW;
        if(h<82) return Color.GREEN;
        if(h<116) return Color.CYAN;
        if(h<151) return Color.PURPLE;
        return Color.PINK;
    }

    private static Candidate chooseShot(List<Ball> balls, Color current) {
        List<Candidate> all=new ArrayList<>();
        double sx=294, sy=1065;
        // Every matching ball gets six possible neighboring landing cells.
        for(Ball t:balls) if(t.c==current) {
            double[][] n={{DX,0},{-DX,0},{DIAGX,DY},{DIAGX,-DY},{-DIAGX,DY},{-DIAGX,-DY}};
            for(double[] q:n){
                double tx=t.x+q[0], ty=t.y+q[1];
                if(tx<20||tx>568||ty<185||ty>520||occupied(tx,ty,balls,40)) continue;
                double score=simulateScore(balls,current,tx,ty);
                addTrajectories(all,sx,sy,tx,ty,score,balls);
            }
        }
        // Also consider shooting toward the centers of matching balls; the collision naturally attaches beside them.
        for(Ball t:balls) if(t.c==current){
            double score=simulateScore(balls,current,t.x,t.y);
            addTrajectories(all,sx,sy,t.x,t.y,score,balls);
        }
        if(all.isEmpty()) return null;
        all.sort(Comparator.comparingDouble((Candidate c)->c.score).reversed());
        Candidate best=all.get(0);
        if(best.score < 20) {
            // A useful miss is preferable to firing into an occupied/unsafe path.
            return best;
        }
        return best;
    }

    private static boolean occupied(double x,double y,List<Ball> balls,double min) {
        for(Ball b:balls) if(Math.hypot(b.x-x,b.y-y)<min) return true;
        return false;
    }

    private static double simulateScore(List<Ball> original, Color color, double px,double py){
        List<Ball> a=new ArrayList<>();
        for(Ball b:original) a.add(new Ball(b.x,b.y,b.r,b.c));
        a.add(new Ball(px,py,R,color));
        int idx=a.size()-1;
        Set<Integer> group=new HashSet<>();
        ArrayDeque<Integer> q=new ArrayDeque<>(); q.add(idx); group.add(idx);
        while(!q.isEmpty()){
            int i=q.remove(); Ball bi=a.get(i);
            for(int j=0;j<a.size();j++) if(!group.contains(j) && a.get(j).c==color && near(bi,a.get(j),59)) {group.add(j);q.add(j);}
        }
        int match=group.size();
        if(match<3) match=0; else {
            List<Ball> rem=new ArrayList<>(); for(int i=0;i<a.size();i++) if(!group.contains(i)) rem.add(a.get(i));
            a=rem;
        }
        int dropped=dropCount(a);
        return match*25.0 + dropped*55.0 - (match==0 ? 30 : 0);
    }

    private static int dropCount(List<Ball> balls){
        if(balls.isEmpty()) return 0;
        boolean[] keep=new boolean[balls.size()]; ArrayDeque<Integer> q=new ArrayDeque<>();
        for(int i=0;i<balls.size();i++) if(balls.get(i).y<225){keep[i]=true;q.add(i);}
        while(!q.isEmpty()){
            int i=q.remove(); for(int j=0;j<balls.size();j++) if(!keep[j] && near(balls.get(i),balls.get(j),59)){keep[j]=true;q.add(j);}
        }
        int n=0; for(boolean k:keep) if(!k)n++; return n;
    }

    private static boolean near(Ball a,Ball b,double d){return Math.hypot(a.x-b.x,a.y-b.y)<d;}

    private static void addTrajectories(List<Candidate> out,double sx,double sy,double tx,double ty,double score,List<Ball> balls){
        // Direct path.
        double a=Math.atan2(ty-sy,tx-sx);
        if(clearPath(sx,sy,tx,ty,balls,tx,ty)) out.add(new Candidate(tx,ty,a,score+12,false));
        // One-bounce bank shots. Mirroring target gives the required initial ray.
        double[] mirrors={-tx, 2*CW-tx};
        for(int side=0;side<2;side++){
            double mx=mirrors[side]; double aa=Math.atan2(ty-sy,mx-sx);
            if(Math.abs(aa)<Math.toRadians(5) || Math.abs(aa)>Math.toRadians(84)) continue;
            double wx=(side==0)?R:CW-R;
            double t=(wx-sx)/(mx-sx); if(t<=0||t>=1) continue;
            double wy=sy+(ty-sy)*t;
            if(wy<190||wy>520) continue;
            if(clearPath(sx,sy,wx,wy,balls,tx,ty) && clearPath(wx,wy,tx,ty,balls,tx,ty))
                out.add(new Candidate(tx,ty,aa,score+20,true));
        }
    }

    private static boolean clearPath(double x1,double y1,double x2,double y2,List<Ball> balls,Double tx,Double ty){
        int steps=(int)Math.max(20,Math.hypot(x2-x1,y2-y1)/7);
        for(int k=1;k<steps;k++){
            double t=k/(double)steps, x=x1+(x2-x1)*t,y=y1+(y2-y1)*t;
            for(Ball b:balls){
                if(tx!=null && Math.hypot(b.x-tx,b.y-ty)<20) continue;
                if(Math.hypot(b.x-x,b.y-y)<(b.r+13)) return false;
            }
        }
        return true;
    }

    private static void fire(double angle,int realW,int realH){
        // dispatchGesture uses real display coordinates. Aim at a point high in the board.
        double len=500;
        double tx=294 + Math.cos(angle)*len;
        double ty=1065 + Math.sin(angle)*len;
        double bestT=len;
        if(Math.sin(angle)<-0.05) bestT=Math.min(bestT,(180-1065)/Math.sin(angle)*0.90);
        if(Math.cos(angle)<-0.05) bestT=Math.min(bestT,(20-294)/Math.cos(angle)*0.90);
        if(Math.cos(angle)>0.05) bestT=Math.min(bestT,(568-294)/Math.cos(angle)*0.90);
        if(bestT>50 && bestT<len){ tx=294+Math.cos(angle)*bestT; ty=1065+Math.sin(angle)*bestT; }
        // For a left/right bank, a long swipe is unnecessary: a tap at the aim point is enough for this game.
        float fx=(float)(tx*realW/CW), fy=(float)(ty*realH/CH);
        fx=Math.max(2,Math.min(realW-2,fx)); fy=Math.max(2,Math.min(realH-2,fy));
        if(GameAccessibilityService.tap(fx,fy)) lastShot=SystemClock.uptimeMillis();
    }
}
