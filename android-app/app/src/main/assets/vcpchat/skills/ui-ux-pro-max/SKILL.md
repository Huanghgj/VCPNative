---
name: ui-ux-pro-max
description: UI/UX design intelligence. 67 styles, 96 palettes, 57 font pairings, 25 charts, 13 stacks (React, Next.js, Vue, Svelte, SwiftUI, React Native, Flutter, Tailwind, shadcn/ui, Jetpack Compose, Astro, NuxtJS, Nuxt UI). Use when user requests UI/UX design, build, create, implement, review, fix, or improve.
---
# ui-ux-pro-max

Comprehensive design guide for web and mobile applications. Contains 67 styles, 96 color palettes, 57 font pairings, 99 UX guidelines, and 25 chart types across 13 technology stacks. Searchable database with priority-based recommendations.

**重要：这是APP本地技能，所有搜索命令通过 <<<[SKILL_EXEC:ui-ux-pro-max]>>> 标记在手机端本地执行。不要使用任何后端服务器工具（如PowerShellExecutor、ServerBashExecutor、ServerPowerShellExecutor等）来执行搜索。只需在回复中输出SKILL_EXEC标记，APP会自动在本地运行BM25搜索引擎并返回结果。**

## How to Use This Skill

When user requests UI/UX work (design, build, create, implement, review, fix, improve), follow this workflow:

### Step 1: Analyze User Requirements

Extract key information from user request:
- **Product type**: SaaS, e-commerce, portfolio, dashboard, landing page, etc.
- **Style keywords**: minimal, playful, professional, elegant, dark mode, etc.
- **Industry**: healthcare, fintech, gaming, education, etc.
- **Stack**: React, Vue, Next.js, or default to `html-tailwind`

### Step 2: Generate Design System (REQUIRED)

**Always start with `--design-system`** to get comprehensive recommendations:

<<<[SKILL_EXEC:ui-ux-pro-max]>>>"<product_type> <industry> <keywords>" --design-system -p "Project Name"<<<[/SKILL_EXEC]>>>

This command searches 5 domains in parallel (product, style, color, landing, typography) and returns a complete design system.

**Example:**
<<<[SKILL_EXEC:ui-ux-pro-max]>>>"beauty spa wellness service" --design-system -p "Serenity Spa"<<<[/SKILL_EXEC]>>>

### Step 3: Supplement with Detailed Searches (as needed)

After getting the design system, use domain searches to get additional details:

<<<[SKILL_EXEC:ui-ux-pro-max]>>>"<keyword>" --domain <domain><<<[/SKILL_EXEC]>>>

**When to use detailed searches:**

| Need | Domain | Example |
|------|--------|---------|
| More style options | `style` | `"glassmorphism dark" --domain style` |
| Chart recommendations | `chart` | `"real-time dashboard" --domain chart` |
| UX best practices | `ux` | `"animation accessibility" --domain ux` |
| Alternative fonts | `typography` | `"elegant luxury" --domain typography` |
| Landing structure | `landing` | `"hero social-proof" --domain landing` |

### Step 4: Stack Guidelines (Default: html-tailwind)

Get implementation-specific best practices:

<<<[SKILL_EXEC:ui-ux-pro-max]>>>"<keyword>" --stack html-tailwind<<<[/SKILL_EXEC]>>>

Available stacks: `html-tailwind`, `react`, `nextjs`, `vue`, `svelte`, `swiftui`, `react-native`, `flutter`, `shadcn`, `jetpack-compose`

---

## Search Reference

### Available Domains

| Domain | Use For | Example Keywords |
|--------|---------|------------------|
| `product` | Product type recommendations | SaaS, e-commerce, portfolio, healthcare |
| `style` | UI styles, colors, effects | glassmorphism, minimalism, dark mode |
| `typography` | Font pairings, Google Fonts | elegant, playful, professional |
| `color` | Color palettes by product type | saas, ecommerce, healthcare, beauty |
| `landing` | Page structure, CTA strategies | hero, testimonial, pricing |
| `chart` | Chart types, library recommendations | trend, comparison, funnel |
| `ux` | Best practices, anti-patterns | animation, accessibility, loading |
| `icons` | Icon sets and libraries | lucide, heroicons, svg |

---

## Common Rules for Professional UI

### Icons & Visual Elements

| Rule | Do | Don't |
|------|----|----- |
| **No emoji icons** | Use SVG icons (Heroicons, Lucide) | Use emojis as UI icons |
| **Stable hover states** | Use color/opacity transitions | Use scale transforms that shift layout |
| **Consistent icon sizing** | Use fixed viewBox (24x24) | Mix different icon sizes |

### Light/Dark Mode Contrast

| Rule | Do | Don't |
|------|----|----- |
| **Glass card light mode** | Use `bg-white/80` or higher | Use `bg-white/10` (too transparent) |
| **Text contrast** | Use `#0F172A` (slate-900) | Use gray-400 for body text |
| **Border visibility** | Use `border-gray-200` in light | Use `border-white/10` (invisible) |

### Layout & Spacing

| Rule | Do | Don't |
|------|----|----- |
| **Floating navbar** | Add `top-4 left-4 right-4` spacing | Stick to `top-0 left-0 right-0` |
| **Content padding** | Account for fixed navbar height | Let content hide behind fixed elements |

---

## Pre-Delivery Checklist

- [ ] No emojis used as icons (use SVG instead)
- [ ] All clickable elements have `cursor-pointer`
- [ ] Hover states provide clear visual feedback
- [ ] Light mode text has sufficient contrast (4.5:1 minimum)
- [ ] Responsive at 375px, 768px, 1024px, 1440px
- [ ] `prefers-reduced-motion` respected
