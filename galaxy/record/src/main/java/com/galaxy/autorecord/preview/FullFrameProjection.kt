package com.galaxy.autorecord.preview

import kotlin.math.*

/** 广角保边映射。中心采用镜头校正，向原片四边连续过渡；不以中心裁切填满画面。 */
internal object FullFrameProjection {
    const val CORE_SCALE = 2.1445069f // tan(65°)，130°中心校正参考；不是输出裁切范围。
    /** 与GPU同一数学合同，供离线覆盖/不折叠校验；输入输出均为原片归一化坐标。 */
    fun source(u:Double,v:Double,p:LensProfile):Pair<Double,Double> {
        val x=(u-.5)*2*CORE_SCALE;val y=(v-.5)*2*CORE_SCALE*p.height/p.width
        val r=hypot(x,y);val theta=atan(r);val t2=theta*theta;val k=p.coefficients
        val d=theta*(1+t2*(k[0]+t2*(k[1]+t2*(k[2]+t2*k[3]))))
        val factor=if(r>1e-6)d/r else 1.0
        val correctedX=p.cx/p.width.toDouble()+p.fx/p.width.toDouble()*x*factor
        val correctedY=p.cy/p.height.toDouble()+p.fy/p.height.toDouble()*y*factor
        val edge=max(abs(u-.5),abs(v-.5))*2
        val strength=(1-edge*edge).coerceIn(0.0,1.0)
        return (u+(correctedX-u)*strength) to (v+(correctedY-v)*strength)
    }
}
