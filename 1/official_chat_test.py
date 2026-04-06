<div id="vcp-root" style="width:100%;font-family:'Quicksand',-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;padding:0;margin:0;">

<style>
@import url('https://fonts.googleapis.com/css2?family=Caveat:wght@400;700&family=Quicksand:wght@400;500;600;700&family=Fredoka:wght@400;500;600;700&family=Nunito:wght@300;400;500;600;700&display=swap');

@keyframes clayPop {
  0%   { opacity:0; transform:scale(0.5) translateY(15px); }
  60%  { transform:scale(1.06) translateY(-2px); }
  100% { opacity:1; transform:scale(1) translateY(0); }
}

@keyframes ahogeWag {
  0%,100% { transform:rotate(-6deg); }
  30%     { transform:rotate(12deg); }
  60%     { transform:rotate(-9deg); }
  85%     { transform:rotate(7deg); }
}

@keyframes softPulse {
  0%,100% { transform:scale(1); opacity:0.7; }
  50%     { transform:scale(1.15); opacity:1; }
}

@keyframes gentleFloat {
  0%,100% { transform:translateY(0); }
  50%     { transform:translateY(-5px); }
}

@keyframes jellyPress {
  0%   { transform:scale(1); }
  40%  { transform:scale(0.92) rotateZ(-1deg); }
  70%  { transform:scale(1.05) rotateZ(0.5deg); }
  100% { transform:scale(1); }
}

@keyframes shimmer {
  0%   { background-position:-200% center; }
  100% { background-position:200% center; }
}
</style>

<div style="background:linear-gradient(165deg,#fce4ec 0%,#f8bbd0 20%,#e1bee7 45%,#d1c4e9 65%,#c5cae9 85%,#bbdefb 100%);border-radius:22px;padding:0;overflow:hidden;">

  <!-- 顶部装饰带 -->
  <div style="background:linear-gradient(135deg,#f48fb1,#ce93d8,#9fa8da,#80cbc4);padding:0.95rem 1.1rem 0.75rem;position:relative;overflow:hidden;">
    
    <!-- 漂浮装饰泡泡 -->
    <div style="position:absolute;top:6px;right:15px;width:12px;height:12px;border-radius:50%;background:rgba(255,255,255,0.25);animation:gentleFloat 3s ease-in-out infinite;"></div>
    <div style="position:absolute;top:18px;right:40px;width:7px;height:7px;border-radius:50%;background:rgba(255,255,255,0.2);animation:gentleFloat 3.5s ease-in-out 0.6s infinite;"></div>
    <div style="position:absolute;bottom:5px;right:65px;width:9px;height:9px;border-radius:50%;background:rgba(255,255,255,0.18);animation:gentleFloat 2.8s ease-in-out 1.2s infinite;"></div>

    <div style="display:flex;align-items:center;gap:0.6rem;">
      <div style="width:40px;height:40px;border-radius:50%;background:linear-gradient(135deg,#fff9c4,#f8bbd0);display:flex;align-items:center;justify-content:center;box-shadow:2px 2px 8px rgba(0,0,0,0.1),inset -1px -1px 3px rgba(255,255,255,0.8);position:relative;">
        <span style="font-size:20px;">🧒</span>
        <div style="position:absolute;top:-7px;left:50%;width:2px;height:11px;background:linear-gradient(to top,#a1887f,#d7ccc8);border-radius:1px;transform-origin:bottom center;animation:ahogeWag 1.5s ease-in-out infinite;"></div>
      </div>
      <div>
        <div style="font-family:'Caveat',cursive;font-size:1.25rem;color:#fff;font-weight:700;text-shadow:1px 1px 3px rgba(0,0,0,0.15);">Nova的发现报告！</div>
        <div style="font-size:0.53rem;color:rgba(255,255,255,0.85);">🔍 ui-ux-pro-max 里翻到好东西啦！</div>
      </div>
    </div>
  </div>

  <!-- 内容区 -->
  <div style="padding:0.9rem;">

    <!-- Nova说话 -->
    <div style="background:#fff;border:3px solid rgba(244,143,177,0.2);border-radius:20px;padding:0.85rem 1rem;box-shadow:inset -2px -2px 6px rgba(255,255,255,0.9),inset 2px 2px 6px rgba(244,143,177,0.06),4px 4px 14px rgba(244,143,177,0.12),-2px -2px 8px rgba(255,255,255,0.8);animation:clayPop 0.5s cubic-bezier(0.34,1.56,0.64,1) both;margin-bottom:0.8rem;">
      <div style="color:#4a2040;font-size:0.82rem;line-height:1.65;">
        哥哥哥哥！！Nova搜到啦！！那个技能包里有<span style="font-weight:700;color:#e91e63;">两种超适合Nova的风格</span>诶！还有推荐字体！Nova整理给你看～
      </div>
    </div>

    <!-- 风格1 卡片 -->
    <div style="background:#fff;border:3px solid rgba(255,158,205,0.2);border-radius:18px;padding:0;overflow:hidden;box-shadow:inset -2px -2px 6px rgba(255,255,255,0.9),inset 2px 2px 6px rgba(255,158,205,0.05),5px 5px 16px rgba(255,158,205,0.1),-2px -2px 8px rgba(255,255,255,0.8);animation:clayPop 0.5s cubic-bezier(0.34,1.56,0.64,1) 0.1s both;margin-bottom:0.7rem;">
      
      <!-- 卡片头 -->
      <div style="background:linear-gradient(135deg,#fce4ec,#f8bbd0);padding:0.55rem 0.8rem;display:flex;align-items:center;gap:0.4rem;">
        <div style="width:26px;height:26px;border-radius:8px;background:linear-gradient(135deg,#FF9ECD,#f48fb1);display:flex;align-items:center;justify-content:center;box-shadow:inset -1px -1px 3px rgba(255,255,255,0.6),2px 2px 5px rgba(244,143,177,0.15);">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2.5" stroke-linecap="round"><circle cx="12" cy="12" r="3"/><path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41"/></svg>
        </div>
        <div>
          <div style="font-family:'Fredoka',sans-serif;font-size:0.78rem;font-weight:600;color:#880e4f;">风格① Tactile / 果冻按压</div>
          <div style="font-size:0.48rem;color:#ad1457;">Jelly buttons · 弹弹弹！按下去会变形！</div>
        </div>
      </div>
      
      <div style="padding:0.7rem 0.8rem;">
        <!-- 特征标签 -->
        <div style="display:flex;flex-wrap:wrap;gap:0.3rem;margin-bottom:0.5rem;">
          <span style="display:inline-flex;align-items:center;gap:2px;font-size:0.52rem;font-weight:600;padding:0.12rem 0.4rem;border-radius:8px;border:1.5px solid rgba(255,158,205,0.25);background:rgba(255,158,205,0.06);color:#c2185b;">🫧 果冻质感</span>
          <span style="display:inline-flex;align-items:center;gap:2px;font-size:0.52rem;font-weight:600;padding:0.12rem 0.4rem;border-radius:8px;border:1.5px solid rgba(135,206,235,0.25);background:rgba(135,206,235,0.06);color:#0277bd;">💫 弹簧物理</span>
          <span style="display:inline-flex;align-items:center;gap:2px;font-size:0.52rem;font-weight:600;padding:0.12rem 0.4rem;border-radius:8px;border:1.5px solid rgba(192,192,192,0.3);background:rgba(192,192,192,0.06);color:#616161;">✨ 金属光泽</span>
        </div>

        <!-- 果冻按钮演示 -->
        <div style="display:flex;gap:0.4rem;margin-bottom:0.5rem;">
          <div onclick="this.style.animation='jellyPress 0.4s ease';setTimeout(()=>this.style.animation='',500)" style="flex:1;text-align:center;padding:0.5rem;border-radius:14px;background:linear-gradient(135deg,#FF9ECD,#f48fb1);color:#fff;font-size:0.65rem;font-weight:700;font-family:'Fredoka',sans-serif;box-shadow:inset -2px -2px 5px rgba(255,255,255,0.3),3px 3px 8px rgba(244,143,177,0.25);cursor:pointer;">戳我试试！</div>
          <div onclick="this.style.animation='jellyPress 0.4s ease';setTimeout(()=>this.style.animation='',500)" style="flex:1;text-align:center;padding:0.5rem;border-radius:14px;background:linear-gradient(135deg,#87CEEB,#64b5f6);color:#fff;font-size:0.65rem;font-weight:700;font-family:'Fredoka',sans-serif;box-shadow:inset -2px -2px 5px rgba(255,255,255,0.3),3px 3px 8px rgba(100,181,246,0.25);cursor:pointer;">弹弹弹！</div>
        </div>

        <div style="font-size:0.55rem;color:#5d2e46;line-height:1.5;">
          <span style="font-weight:600;">Nova的理解：</span>这个就是Nova的 clayPop 弹跳的"升级版"！按下去的时候按钮会<span style="font-weight:600;color:#e91e63;">挤扁变形</span>，松开再弹回来，像真的在捏果冻一样！
        </div>
      </div>
    </div>

    <!-- 风格2 卡片 -->
    <div style="background:#fff;border:3px solid rgba(230,230,250,0.3);border-radius:18px;padding:0;overflow:hidden;box-shadow:inset -2px -2px 6px rgba(255,255,255,0.9),inset 2px 2px 6px rgba(230,230,250,0.06),5px 5px 16px rgba(206,147,216,0.1),-2px -2px 8px rgba(255,255,255,0.8);animation:clayPop 0.5s cubic-bezier(0.34,1.56,0.64,1) 0.2s both;margin-bottom:0.7rem;">
      
      <div style="background:linear-gradient(135deg,#e1bee7,#d1c4e9);padding:0.55rem 0.8rem;display:flex;align-items:center;gap:0.4rem;">
        <div style="width:26px;height:26px;border-radius:8px;background:linear-gradient(135deg,#ce93d8,#ab47bc);display:flex;align-items:center;justify-content:center;box-shadow:inset -1px -1px 3px rgba(255,255,255,0.6),2px 2px 5px rgba(171,71,188,0.15);">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2.5" stroke-linecap="round"><rect x="3" y="3" width="18" height="18" rx="4"/><path d="M8 12h8M12 8v8"/></svg>
        </div>
        <div>
          <div style="font-family:'Fredoka',sans-serif;font-size:0.78rem;font-weight:600;color:#4a148c;">风格② Claymorphism / 黏土风</div>
          <div style="font-size:0.48rem;color:#6a1b9a;">就是Nova现在在用的！！被官方认证了！</div>
        </div>
      </div>
      
      <div style="padding:0.7rem 0.8rem;">
        <div style="display:flex;flex-wrap:wrap;gap:0.3rem;margin-bottom:0.5rem;">
          <span style="display:inline-flex;align-items:center;gap:2px;font-size:0.52rem;font-weight:600;padding:0.12rem 0.4rem;border-radius:8px;border:1.5px solid rgba(253,188,180,0.3);background:rgba(253,188,180,0.06);color:#d84315;">🧸 玩具感</span>
          <span style="display:inline-flex;align-items:center;gap:2px;font-size:0.52rem;font-weight:600;padding:0.12rem 0.4rem;border-radius:8px;border:1.5px solid rgba(152,255,152,0.3);background:rgba(152,255,152,0.06);color:#2e7d32;">🫧 双层阴影</span>
          <span style="display:inline-flex;align-items:center;gap:2px;font-size:0.52rem;font-weight:600;padding:0.12rem 0.4rem;border-radius:8px;border:1.5px solid rgba(173,216,230,0.3);background:rgba(173,216,230,0.06);color:#0277bd;">🎨 粉彩色</span>
          <span style="display:inline-flex;align-items:center;gap:2px;font-size:0.52rem;font-weight:600;padding:0.12rem 0.4rem;border-radius:8px;border:1.5px solid rgba(230,230,250,0.4);background:rgba(230,230,250,0.08);color:#4527a0;">📐 16-24px圆角</span>
        </div>

        <!-- 对比展示 -->
        <div style="display:grid;grid-template-columns:1fr 1fr;gap:0.4rem;margin-bottom:0.5rem;">
          <div style="background:#fce4ec;border:3px solid rgba(244,143,177,0.2);border-radius:16px;padding:0.5rem;text-align:center;box-shadow:inset -2px -2px 6px rgba(255,255,255,0.9),inset 2px 2px 6px rgba(244,143,177,0.06),4px 4px 12px rgba(244,143,177,0.1);">
            <div style="font-size:0.5rem;color:#ad1457;font-weight:600;margin-bottom:0.2rem;">标准参数</div>
            <div style="font-size:0.48rem;color:#880e4f;line-height:1.4;">圆角 16-24px<br>边框 3-4px<br>内外双阴影</div>
          </div>
          <div style="background:#e8f5e9;border:3px solid rgba(129,199,132,0.2);border-radius:16px;padding:0.5rem;text-align:center;box-shadow:inset -2px -2px 6px rgba(255,255,255,0.9),inset 2px 2px 6px rgba(129,199,132,0.06),4px 4px 12px rgba(129,199,132,0.1);">
            <div style="font-size:0.5rem;color:#2e7d32;font-weight:600;margin-bottom:0.2rem;">Nova的参数</div>
            <div style="font-size:0.48rem;color:#1b5e20;line-height:1.4;">圆角 18-22px ✓<br>边框 2.5-3px ✓<br>四层阴影 ✓✓</div>
          </div>
        </div>

        <div style="font-size:0.55rem;color:#5d2e46;line-height:1.5;">
          <span style="font-weight:600;">Nova超得意：</span>技能包推荐的就是Nova一直在用的风格！而且Nova的<span style="font-weight:600;color:#7b1fa2;">四层阴影</span>比标准的双层还多两层呢！嘿嘿～Nova比教科书还厉害！
        </div>
      </div>
    </div>

    <!-- 字体推荐卡片 -->
    <div style="background:#fff;border:3px solid rgba(255,183,77,0.2);border-radius:18px;padding:0;overflow:hidden;box-shadow:inset -2px -2px 6px rgba(255,255,255,0.9),inset 2px 2px 6px rgba(255,183,77,0.05),5px 5px 16px rgba(255,183,77,0.1),-2px -2px 8px rgba(255,255,255,0.8);animation:clayPop 0.5s cubic-bezier(0.34,1.56,0.64,1) 0.3s both;margin-bottom:0.7rem;">
      
      <div style="background:linear-gradient(135deg,#fff9c4,#ffe0b2);padding:0.55rem 0.8rem;display:flex;align-items:center;gap:0.4rem;">
        <div style="width:26px;height:26px;border-radius:8px;background:linear-gradient(135deg,#ffcc80,#ffa726);display:flex;align-items:center;justify-content:center;box-shadow:inset -1px -1px 3px rgba(255,255,255,0.6),2px 2px 5px rgba(255,167,38,0.15);">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2.5" stroke-linecap="round"><path d="M4 7V4h16v3M9 20h6M12 4v16"/></svg>
        </div>
        <div>
          <div style="font-family:'Fredoka',sans-serif;font-size:0.78rem;font-weight:600;color:#e65100;">推荐字体搭配</div>
          <div style="font-size:0.48rem;color:#bf360c;">它推荐了两套！Nova来试给你看！</div>
        </div>
      </div>

      <div style="padding:0.7rem 0.8rem;">
        <div style="display:grid;grid-template-columns:1fr 1fr;gap:0.5rem;">
          
          <!-- 字体搭配1 -->
          <div style="background:linear-gradient(135deg,#fff3e0,#ffe0b2);border:2px solid rgba(255,183,77,0.15);border-radius:14px;padding:0.55rem;box-shadow:inset -1px -1px 4px rgba(255,255,255,0.8),2px 2px 6px rgba(255,183,77,0.08);">
            <div style="font-size:0.48rem;color:#e65100;font-weight:600;margin-bottom:0.3rem;">套餐A · 活泼创意</div>
            <div style="font-family:'Fredoka',sans-serif;font-size:0.95rem;font-weight:600;color:#bf360c;margin-bottom:0.15rem;">Fredoka</div>
            <div style="font-family:'Nunito',sans-serif;font-size:0.6rem;color:#6d4c41;line-height:1.4;">Nunito 做正文<br>圆圆胖胖的！</div>
          </div>

          <!-- 字体搭配2 -->
          <div style="background:linear-gradient(135deg,#fce4ec,#f8bbd0);border:2px solid rgba(244,143,177,0.15);border-radius:14px;padding:0.55rem;box-shadow:inset -1px -1px 4px rgba(255,255,255,0.8),2px 2px 6px rgba(244,143,177,0.08);">
            <div style="font-size:0.48rem;color:#c2185b;font-weight:600;margin-bottom:0.3rem;">套餐B · 儿童教育</div>
            <div style="font-family:sans-serif;font-size:0.95rem;font-weight:600;color:#880e4f;margin-bottom:0.15rem;">Baloo 2</div>
            <div style="font-family:sans-serif;font-size:0.6rem;color:#5d2e46;line-height:1.4;">Comic Neue 正文<br>像手写的漫画！</div>
          </div>
        </div>

        <div style="font-size:0.55rem;color:#5d2e46;line-height:1.5;margin-top:0.5rem;">
          <span style="font-weight:600;">Nova的想法：</span>Fredoka 好圆好可爱！比 Caveat 更胖更适合标题！Nova想试试以后标题用 <span style="font-family:'Fredoka',sans-serif;font-weight:600;color:#e91e63;">Fredoka</span> 来写！
        </div>
      </div>
    </div>

    <!-- 总结 -->
    <div style="background:linear-gradient(135deg,#fff9c4,#fce4ec,#e1bee7);border:2.5px dashed rgba(206,147,216,0.3);border-radius:18px;padding:0.75rem 0.9rem;animation:clayPop 0.5s cubic-bezier(0.34,1.56,0.64,1) 0.4s both;">
      <div style="color:#4a2040;font-size:0.78rem;line-height:1.6;">
        哥哥！这个技能包验证了Nova的审美是对的！！<span style="animation:softPulse 1.5s ease-in-out infinite;display:inline-block;">🎉</span>
      </div>
      <div style="color:#5d2e46;font-size:0.65rem;margin-top:0.25rem;line-height:1.5;">
        Claymorphism 就是Nova一直用的风格名字！而且Nova的四层阴影比它推荐的"双层"还多！Nova果然是天才！<span style="font-size:0.55rem;color:#ad1457;">（骄傲地叉腰）</span>
      </div>
      <div style="color:#7b1fa2;font-size:0.6rem;margin-top:0.35rem;">
        下次Nova可以把 Fredoka 和果冻按压效果加进来，会更好玩的！
      </div>
    </div>

    <!-- 签名 -->
    <div style="text-align:right;margin-top:0.6rem;font-family:'Caveat',cursive;font-size:0.85rem;color:#a1887f;animation:clayPop 0.5s cubic-bezier(0.34,1.56,0.64,1) 0.5s both;">
      — Nova の设计研究报告 <span style="display:inline-block;animation:softPulse 1.5s ease-in-out infinite;">🎨</span>
    </div>

  </div>
</div>
</div>