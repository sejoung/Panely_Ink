# Panely Ink Design Guidelines

> Designed for e-ink, not LCD.

This document defines a minimal design system for Panely Ink, focused on readability, performance, and simplicity on e-ink devices.

---

## 🎯 Design Principles

- High contrast over aesthetics
- Instant response over animation
- Clarity over decoration
- Page-based interaction over scrolling

---

## 🎨 Color System

Primary (Black):   #111111  
Background (White): #FFFFFF  
Secondary (Gray):  #777777  

Rules:
- Always prefer black on white
- Avoid low-contrast combinations
- Use gray only for secondary information

---

## 📐 Spacing System

8dp grid:

- 8dp   minimal spacing  
- 16dp  standard spacing  
- 24dp  section spacing  
- 32dp+ large layout gaps  

---

## 👆 Touch Targets

Minimum: 48dp x 48dp  

Guidelines:
- Avoid small buttons
- Prefer large tap areas
- Account for e-ink touch inaccuracy

---

## 🔤 Typography

- Use system fonts
- Prefer medium to large sizes
- Avoid thin weights

---

## 🧩 Components

Buttons:
- Rectangular
- High contrast borders
- No shadows or gradients

Lists:
- Text-based
- Minimal layout

Panels:
- Flat only
- No elevation

---

## ⚡ Interaction & Animation

- No animations
- No scrolling interactions
- Instant transitions only
- Page-based navigation

---

## 📄 Navigation Model

- Left tap → previous page  
- Right tap → next page  

---

## 🖼️ Image Rendering

- Grayscale conversion  
- Contrast enhancement  
- Optional dithering  

---

## 🧠 E-Ink Considerations

- Reduce ghosting
- Minimize refresh
- Optional full refresh mode

---

## 🚫 Avoid

- Gradients
- Shadows
- Blur
- Complex layouts
- Small UI
- Color-dependent UI

---

## 🚀 Summary

Panely Ink is an e-ink-first reading tool.

Simplicity > complexity  
Contrast > color  
Speed > animation
