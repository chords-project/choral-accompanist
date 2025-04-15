package tracing

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"os/signal"

	otelpyroscope "github.com/grafana/otel-profiling-go"
	"github.com/grafana/pyroscope-go"
	slogmulti "github.com/samber/slog-multi"
	"go.opentelemetry.io/contrib/bridges/otelslog"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlplog/otlploggrpc"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/log/global"
	"go.opentelemetry.io/otel/propagation"
	sdklog "go.opentelemetry.io/otel/sdk/log"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.26.0"
)

func newTraceProvider(ctx context.Context, serviceName string, otlpAddress string) (*sdktrace.TracerProvider, error) {
	// Ensure default SDK resources and the required service name are set.
	r, err := resource.Merge(
		resource.Default(),
		resource.NewWithAttributes(
			semconv.SchemaURL,
			semconv.ServiceName(serviceName),
		),
	)
	if err != nil {
		return nil, err
	}

	traceExporter, err := otlptracegrpc.New(ctx, otlptracegrpc.WithEndpoint(otlpAddress), otlptracegrpc.WithInsecure())
	if err != nil {
		return nil, err
	}

	// traceExporter, err := stdouttrace.New(
	// 	stdouttrace.WithPrettyPrint())
	// if err != nil {
	// 	return nil, err
	// }

	return sdktrace.NewTracerProvider(
		sdktrace.WithSampler(sdktrace.AlwaysSample()),
		// sdktrace.WithBatcher(traceExporter),
		sdktrace.WithSpanProcessor(
			// sdktrace.NewSimpleSpanProcessor(traceExporter),
			sdktrace.NewBatchSpanProcessor(traceExporter),
		),
		sdktrace.WithResource(r),
	), nil
}

func newLoggerProvider(ctx context.Context, serviceName string, otlpAddress string) (*sdklog.LoggerProvider, error) {
	r, err := resource.Merge(
		resource.Default(),
		resource.NewWithAttributes(
			semconv.SchemaURL,
			semconv.ServiceName(serviceName),
		),
	)
	if err != nil {
		return nil, err
	}

	logExporter, err := otlploggrpc.New(ctx, otlploggrpc.WithEndpoint(otlpAddress), otlploggrpc.WithInsecure())
	if err != nil {
		return nil, err
	}

	loggerProvider := sdklog.NewLoggerProvider(
		sdklog.WithProcessor(sdklog.NewBatchProcessor(logExporter)),
		sdklog.WithResource(r),
	)
	return loggerProvider, nil
}

func initProfiling(serviceName string, pyroscopeAddress string) (*pyroscope.Profiler, error) {
	return pyroscope.Start(pyroscope.Config{
		ApplicationName: serviceName,
		ServerAddress:   pyroscopeAddress,

		Logger: nil, // pyroscope.StandardLogger,

		// you can provide static tags via a map:
		Tags: map[string]string{"hostname": os.Getenv("HOSTNAME")},

		ProfileTypes: []pyroscope.ProfileType{
			// these profile types are enabled by default:
			pyroscope.ProfileCPU,
			pyroscope.ProfileAllocObjects,
			pyroscope.ProfileAllocSpace,
			pyroscope.ProfileInuseObjects,
			pyroscope.ProfileInuseSpace,

			// these profile types are optional:
			pyroscope.ProfileGoroutines,
			pyroscope.ProfileMutexCount,
			pyroscope.ProfileMutexDuration,
			pyroscope.ProfileBlockCount,
			pyroscope.ProfileBlockDuration,
		},
	})
}

// setupOTelSDK bootstraps the OpenTelemetry pipeline.
// If it does not return an error, make sure to call shutdown for proper cleanup.
func SetupOTelSDK(serviceName string, otlpAddr string, pyroscopeAddress string) (shutdown func(context.Context) error, err error) {

	enableTelemetry := os.Getenv("ENABLE_TELEMETRY")
	if enableTelemetry != "1" {
		slog.Debug("Telemetry disabled, enable by setting ENABLE_TELEMETRY=1")
		return func(context.Context) error { return nil }, nil
	}

	// Handle SIGINT (CTRL+C) gracefully.
	ctx, _ := signal.NotifyContext(context.Background(), os.Interrupt)

	var shutdownFuncs []func(context.Context) error

	// shutdown calls cleanup functions registered via shutdownFuncs.
	// The errors from the calls are joined.
	// Each registered cleanup will be invoked once.
	shutdown = func(ctx context.Context) error {
		var err error
		for _, fn := range shutdownFuncs {
			err = errors.Join(err, fn(ctx))
		}
		shutdownFuncs = nil
		return err
	}

	// handleErr calls shutdown for cleanup and makes sure that all errors are returned.
	handleErr := func(inErr error) {
		err = errors.Join(inErr, shutdown(ctx))
	}

	// Set up propagator.
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(
		propagation.TraceContext{},
		propagation.Baggage{},
	))

	// Set up trace provider.
	tracerProvider, err := newTraceProvider(ctx, serviceName, otlpAddr)
	if err != nil {
		handleErr(err)
		return
	}
	shutdownFuncs = append(shutdownFuncs, tracerProvider.Shutdown)

	slog.Info(fmt.Sprintf("Setting trace provider: %v", tracerProvider))
	otel.SetTracerProvider(otelpyroscope.NewTracerProvider(tracerProvider))

	_, _ = pyroscope.Start(pyroscope.Config{
		ApplicationName: "my-service",
		ServerAddress:   "http://localhost:4040",
	})

	// // Set up meter provider.
	// meterProvider, err := newMeterProvider()
	// if err != nil {
	// 	handleErr(err)
	// 	return
	// }
	// shutdownFuncs = append(shutdownFuncs, meterProvider.Shutdown)
	// otel.SetMeterProvider(meterProvider)

	// // Set up logger provider.
	loggerProvider, err := newLoggerProvider(ctx, serviceName, otlpAddr)
	if err != nil {
		handleErr(err)
		return
	}
	shutdownFuncs = append(shutdownFuncs, loggerProvider.Shutdown)
	global.SetLoggerProvider(loggerProvider)

	otelLogger := otelslog.NewLogger("frontend", otelslog.WithLoggerProvider(loggerProvider))

	logger := slog.New(
		slogmulti.Fanout(
			otelLogger.Handler(),
			slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{}),
		),
	)

	slog.SetDefault(logger)

	// Set up profiling
	profiler, err := initProfiling(serviceName, pyroscopeAddress)
	if err != nil {
		handleErr(err)
		return
	}
	shutdownFuncs = append(shutdownFuncs, func(ctx context.Context) error { return profiler.Stop() })

	slog.Info("OpenTelemetry initialized")
	return
}
