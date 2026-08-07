;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.gui.textview
  "A multi-line `[:text-view ...]` for glimmer hiccup.

  glimmer ships `:entry`, which is one line. A run's problem statement is
  the opposite of one line — the covering campaign's prompts run to several
  paragraphs — so starting a run from the GUI needs a real text area.

  Registered through `glimmer.widget/register-widget!` rather than added to
  glimmer itself, which is what that hook exists for (glimmer-gl adds
  `:gl-area` the same way). Nothing here is veriframe-specific; it lives
  here because this is the only app that needs it so far.

  TWO THINGS ARE NOT OBVIOUS.

  The text lives on the buffer, not the widget. GtkTextView is a view onto a
  GtkTextBuffer, so `changed` fires on the buffer and glimmer's uniform
  void(widget,data) signal path cannot reach it — hence the custom :connect.

  :apply compares before it sets. GTK has no idea a re-render is happening,
  so setting the buffer to text it already holds still moves the cursor to
  the end and drops the selection: type into the box, let any unrelated
  ratom change trigger a render, and the caret jumps. Reading the buffer
  first and setting only on a real difference makes a re-render a no-op,
  which also breaks the changed -> ratom -> re-render -> changed loop
  without needing glimmer's private suppression set."
  (:require [glimmer.ffi :as g]
            [glimmer.widget :as w]
            [jolt.ffi :as ffi]))

;; --- GtkTextView / GtkTextBuffer ---------------------------------------------

(ffi/defcfn gtk-text-view-new "gtk_text_view_new" [] :pointer)
(ffi/defcfn gtk-text-view-get-buffer "gtk_text_view_get_buffer" [:pointer] :pointer)
(ffi/defcfn gtk-text-view-set-wrap-mode "gtk_text_view_set_wrap_mode" [:pointer :int] :void)
(ffi/defcfn gtk-text-view-set-monospace "gtk_text_view_set_monospace" [:pointer :int] :void)
(ffi/defcfn gtk-text-view-set-editable "gtk_text_view_set_editable" [:pointer :int] :void)
(ffi/defcfn gtk-text-view-set-left-margin "gtk_text_view_set_left_margin" [:pointer :int] :void)
(ffi/defcfn gtk-text-view-set-top-margin "gtk_text_view_set_top_margin" [:pointer :int] :void)

(ffi/defcfn gtk-text-buffer-set-text
  "gtk_text_buffer_set_text" [:pointer :string :int] :void)
(ffi/defcfn gtk-text-buffer-get-start-iter
  "gtk_text_buffer_get_start_iter" [:pointer :pointer] :void)
(ffi/defcfn gtk-text-buffer-get-end-iter
  "gtk_text_buffer_get_end_iter" [:pointer :pointer] :void)
;; Returns g_malloc'd memory, so it is taken as a raw pointer, copied into a
;; jolt string, and freed here. Bound as :string it would be marshalled and
;; then leaked once per keystroke.
(ffi/defcfn gtk-text-buffer-get-text
  "gtk_text_buffer_get_text" [:pointer :pointer :pointer :int] :pointer)
(ffi/defcfn g-free "g_free" [:pointer] :void)

(def ^:private wrap-word-char
  "GtkWrapMode: NONE 0, CHAR 1, WORD 2, WORD_CHAR 3. Word-char so a long
  unbroken token — a modulus set printed without spaces — still wraps."
  3)

(def ^:private text-iter-bytes
  "sizeof(GtkTextIter) is 80 on 64-bit; GTK documents it as opaque and
  stack-allocatable, so over-allocating is the portable way to say it."
  128)

(defn buffer-text
  "The whole buffer as a string."
  [buffer]
  (let [start (ffi/alloc text-iter-bytes)
        end (ffi/alloc text-iter-bytes)]
    (try
      (gtk-text-buffer-get-start-iter buffer start)
      (gtk-text-buffer-get-end-iter buffer end)
      (let [p (gtk-text-buffer-get-text buffer start end 0)]
        (if (ffi/null? p)
          ""
          (try (or (ffi/ptr->string p) "") (finally (g-free p)))))
      (finally
        (ffi/free start)
        (ffi/free end)))))

(defn view-text
  "The text currently shown by a text view."
  [view]
  (buffer-text (gtk-text-view-get-buffer view)))

(defn set-view-text!
  "Replace a view's text, but only when it actually differs — see the ns
  docstring on why an unconditional set is not harmless."
  [view s]
  (let [buffer (gtk-text-view-get-buffer view)
        s (str s)]
    (when (not= s (buffer-text buffer))
      (gtk-text-buffer-set-text buffer s -1))))

(defn- text-view-connect!
  "Wire :on-text to the BUFFER's changed signal, handing the handler the
  full text. Retained for the process lifetime like every other glimmer
  callback, so GTK's raw pointer never dangles.

  Deliberately NOT called :on-change. glimmer maps that key to the GTK
  signal `changed` and wires it generically to the widget before :connect
  runs — and `changed` belongs to the buffer, not the view, so a text view
  carrying :on-change earns a GLib-GObject-CRITICAL for the invalid signal
  on top of the correct connection made here."
  [view props]
  (when-let [on-text (:on-text props)]
    (let [buffer (gtk-text-view-get-buffer view)
          cb (ffi/foreign-callable
              (fn [b _] (on-text (buffer-text b)))
              [:pointer :pointer] :void :collect-safe)]
      (w/retain-callable! cb)
      (g/g-signal-connect-data buffer "changed" cb ffi/null ffi/null
                               g/CONNECT-DEFAULT))))

(defn text-view-spec []
  {:ctor (fn [props]
           (let [v (gtk-text-view-new)]
             (gtk-text-view-set-wrap-mode v wrap-word-char)
             (gtk-text-view-set-left-margin v 6)
             (gtk-text-view-set-top-margin v 6)
             (when (:monospace props) (gtk-text-view-set-monospace v 1))
             v))
   :apply (fn [v props]
            (when (contains? props :text) (set-view-text! v (:text props)))
            (when (contains? props :monospace)
              (gtk-text-view-set-monospace v (if (:monospace props) 1 0)))
            (when (contains? props :editable)
              (gtk-text-view-set-editable v (if (false? (:editable props)) 0 1))))
   :connect text-view-connect!
   :container :none})

(w/register-widget! :text-view (text-view-spec))
