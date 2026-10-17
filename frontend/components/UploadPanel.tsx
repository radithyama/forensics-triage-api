"use client";

import { useRef, useState } from "react";
import { PLUGINS } from "@/lib/types";

interface Props {
  disabled: boolean;
  onSubmit: (file: File, plugin: string) => void;
}

function formatBytes(n: number) {
  const u = ["B", "KB", "MB", "GB", "TB"];
  let i = 0;
  while (n >= 1024 && i < u.length - 1) {
    n /= 1024;
    i++;
  }
  return `${n.toFixed(i ? 1 : 0)} ${u[i]}`;
}

export function UploadPanel({ disabled, onSubmit }: Props) {
  const [file, setFile] = useState<File | null>(null);
  const [plugin, setPlugin] = useState<string>(PLUGINS[0]);
  const [dragging, setDragging] = useState(false);
  const input = useRef<HTMLInputElement>(null);

  return (
    <div className="upload">
      <div
        className={`dropzone${dragging ? " dropzone--active" : ""}${disabled ? " dropzone--disabled" : ""}`}
        onClick={() => !disabled && input.current?.click()}
        onDragOver={(e) => {
          e.preventDefault();
          if (!disabled) setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(e) => {
          e.preventDefault();
          setDragging(false);
          if (!disabled && e.dataTransfer.files[0]) setFile(e.dataTransfer.files[0]);
        }}
        role="button"
        tabIndex={0}
        onKeyDown={(e) => (e.key === "Enter" || e.key === " ") && !disabled && input.current?.click()}
      >
        <input
          ref={input}
          type="file"
          hidden
          accept=".raw,.mem,.vmem,.dmp,.lime,.img,.bin"
          onChange={(e) => setFile(e.target.files?.[0] ?? null)}
        />
        {file ? (
          <>
            <strong className="mono">{file.name}</strong>
            <span>{formatBytes(file.size)}. Click to choose a different file</span>
          </>
        ) : (
          <>
            <strong>Drop a memory image here</strong>
            <span>.raw, .mem, .vmem, .dmp, .lime (uploaded straight to Cloud Storage; hashes verified before analysis)</span>
          </>
        )}
      </div>

      <div className="upload__row">
        <label className="field">
          <span>Volatility plugin</span>
          <select value={plugin} onChange={(e) => setPlugin(e.target.value)} disabled={disabled}>
            {PLUGINS.map((p) => (
              <option key={p}>{p}</option>
            ))}
          </select>
        </label>
        <button className="btn btn--primary" disabled={disabled || !file} onClick={() => file && onSubmit(file, plugin)}>
          Start analysis
        </button>
      </div>
    </div>
  );
}

export { formatBytes };
