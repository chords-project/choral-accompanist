{{- define "hotel-reservation.templates.baseService" }}
apiVersion: v1
kind: Service
metadata:
  name: {{ .Values.name }}-{{ include "hotel-reservation.fullname" . }}
spec:
  type: {{ .Values.serviceType | default .Values.global.serviceType }}
  ports:
  {{- range .Values.ports }}
  - name: "{{ .port }}"
    port: {{ .port }}
    {{- if .protocol}}
    protocol: {{ .protocol }}
    {{- end }}
    targetPort: {{ .targetPort }}
  {{- end -}}
  {{- if .Values.sidecar }}
  - name: "5401"
    port: 5401
    targetPort: 5401
  {{- range .Values.sidecar.ports }}
  - name: "{{ . }}"
    port: {{ . }}
    targetPort: {{ . }}
  {{- end }}
  {{- end }}
  selector:
    {{- include "hotel-reservation.selectorLabels" . | nindent 4 }}
    service: {{ .Values.name }}-{{ include "hotel-reservation.fullname" . }}
{{- end }}
